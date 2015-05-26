package com.keepit.normalizer

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.net.URI
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model._
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.integrity.{ URIMigration, UriIntegrityPlugin }
import scala.concurrent.{ ExecutionContext, Future }
import com.keepit.common.db.slick.Database
import com.keepit.common.core._
import com.keepit.common.db.Id
import scala.util.{ Failure, Success, Try }
import com.keepit.common.performance._
import com.keepit.common.time._

@ImplementedBy(classOf[NormalizationServiceImpl])
trait NormalizationService {
  def update(currentReference: NormalizationReference, candidates: Set[NormalizationCandidate]): Future[Option[Id[NormalizedURI]]]
  def prenormalize(uriString: String): Try[String]
}

@Singleton
class NormalizationServiceImpl @Inject() (
    db: Database,
    failedContentCheckRepo: FailedContentCheckRepo,
    normalizedURIRepo: NormalizedURIRepo,
    uriIntegrityPlugin: UriIntegrityPlugin,
    priorKnowledge: PriorNormalizationKnowledge,
    implicit val executionContext: ExecutionContext,
    airbrake: AirbrakeNotifier) extends NormalizationService with Logging {

  private val tmpDisable = """https:\/\/twitter\.com\/.*\/status\/.*""".r

  def prenormalize(uriString: String): Try[String] = priorKnowledge.prenormalize(uriString)

  def update(currentReference: NormalizationReference, candidates: Set[NormalizationCandidate]): Future[Option[Id[NormalizedURI]]] = timing(s"NormalizationService.update.${currentReference.url}") {

    val iter = tmpDisable.findAllMatchIn(currentReference.url)
    if (iter.hasNext) {
      Future.successful(None) // no update
    } else {
      val relevantCandidates = getRelevantCandidates(currentReference, candidates)
      log.info(s"[update($currentReference,${candidates.mkString(",")})] relevantCandidates=${relevantCandidates.mkString(",")}")
      val futureResult = for {
        betterReferenceOption <- processUpdate(currentReference, relevantCandidates)
        betterReferenceOptionAfterAdditionalUpdates <- processAdditionalUpdates(currentReference, betterReferenceOption)
      } yield betterReferenceOptionAfterAdditionalUpdates.map(_.uriId)
      futureResult tap (_.onFailure {
        case e => airbrake.notify(s"Normalization update failed for ${currentReference.url}. Supplied candidates: ${candidates mkString ", "} - Relevant candidates: ${relevantCandidates mkString ", "}", e)
      })
    }
  }

  private def processUpdate(currentReference: NormalizationReference, candidates: Set[NormalizationCandidate]): Future[Option[NormalizationReference]] = timing(s"NormalizationService.processUpdate.${currentReference.url}") {
    val now = currentDateTime
    val recentFailedChecks = db.readOnlyReplica { implicit s => failedContentCheckRepo.getRecentCountByURL(currentReference.url, now.minusMinutes(5)) }
    val somethingBadIsHappening = {
      // Léo: Please improve/fix this properly
      currentReference.url.length >= URLFactory.MAX_URL_SIZE || candidates.exists(_.url.length >= URLFactory.MAX_URL_SIZE)
    }
    if (recentFailedChecks > 10 || somethingBadIsHappening) {
      log.warn(s"[NormalizationService] Stopping normalization for ${currentReference.uriId}. ${currentReference.url}. Candidates: ${candidates.map(_.url).mkString("  ")}")
      Future.successful(None)
    } else {
      log.debug(s"[processUpdate($currentReference,${candidates.mkString(",")})")
      val contentChecks = {
        URI.parse(currentReference.url) match {
          // for debugging bad reference urls -- this is the only place that invokes getContentChecks
          case Success(uri) => log.debug(s"[processUpdate-check] currRef=$currentReference parsed-uri=$uri")
          case Failure(t) => throw new IllegalArgumentException(s"[processUpdate-check] -- failed to parse currRef=$currentReference; Exception=$t; Cause=${t.getCause}", t)
        }
        priorKnowledge.getContentChecks(currentReference.url, currentReference.signature)
      }
      val findStrongerCandidate = FindStrongerCandidate(currentReference, Action(currentReference, contentChecks))

      for { (successfulCandidateOption, weakerCandidates) <- findStrongerCandidate(candidates) } yield {
        contentChecks foreach persistFailedAttempts
        for {
          successfulCandidate <- successfulCandidateOption
          betterReference <- migrate(currentReference, successfulCandidate, weakerCandidates)
        } yield betterReference
      }
    }
  }

  private def getRelevantCandidates(currentReference: NormalizationReference, candidates: Set[NormalizationCandidate]): Set[NormalizationCandidate] = timing(s"NormalizationService.getRelevantCandidates.${currentReference.url}") {
    val prenormalizedCandidates: Set[NormalizationCandidate] = candidates.map {
      case verifiedCandidate: VerifiedCandidate => Success(verifiedCandidate)
      case ScrapedCandidate(url, normalization) => prenormalize(url).map(ScrapedCandidate(_, normalization))
      case AlternateCandidate(url, normalization) => prenormalize(url).map(AlternateCandidate(_, normalization))
      case UntrustedCandidate(url, normalization) => prenormalize(url).map(UntrustedCandidate(_, normalization))
    }.map(_.toOption).flatten

    val allCandidates =
      if (currentReference.isNew || currentReference.normalization.isEmpty)
        prenormalizedCandidates ++ findVariations(currentReference.url).map { case (normalization, uri) => VerifiedCandidate(uri.url, uri.normalization.getOrElse(normalization)) }
      else
        prenormalizedCandidates

    allCandidates.filter(isRelevant(currentReference, _))
  }

  private def findVariations(referenceUrl: String): Seq[(Normalization, NormalizedURI)] = {
    val variations = SchemeNormalizer.generateVariations(referenceUrl)
    val uris = db.readOnlyMaster { implicit session => normalizedURIRepo.getByNormalizedUrls(variations.map(_._2)) }
    variations.collect { case (normalization, urlVariation) if uris.contains(urlVariation) => (normalization, uris(urlVariation)) }
  }

  private def isRelevant(currentReference: NormalizationReference, candidate: NormalizationCandidate): Boolean = {
    currentReference.normalization.isEmpty ||
      currentReference.normalization.get < candidate.normalization ||
      (currentReference.normalization.get == candidate.normalization && currentReference.url != candidate.url)
  }

  private case class FindStrongerCandidate(currentReference: NormalizationReference, action: NormalizationCandidate => Action) {

    def apply(candidates: Set[NormalizationCandidate]): Future[(Option[NormalizationCandidate], Seq[NormalizationCandidate])] = {
      val sortedCandidates = candidates.toSeq.sortBy(_.normalization).reverse
      findCandidate(sortedCandidates)
    }

    def findCandidate(orderedCandidates: Seq[NormalizationCandidate]): Future[(Option[NormalizationCandidate], Seq[NormalizationCandidate])] = {
      orderedCandidates match {
        case Seq() => Future.successful((None, Seq()))
        case Seq(strongerCandidate, weakerCandidates @ _*) =>
          assert(weakerCandidates.isEmpty || weakerCandidates.head.normalization <= strongerCandidate.normalization, s"Normalization candidates ${weakerCandidates.head} and $strongerCandidate have not been sorted properly for $currentReference")
          assert(currentReference.normalization.isEmpty || currentReference.normalization.get <= strongerCandidate.normalization, s"Normalization candidate $strongerCandidate has not been filtered properly for $currentReference")

          action(strongerCandidate) match {
            case Accept => Future.successful((Some(strongerCandidate), weakerCandidates))
            case Reject => findCandidate(weakerCandidates)
            case Check(contentCheck) =>
              if (currentReference.url == strongerCandidate.url) Future.successful(Some(strongerCandidate), weakerCandidates)
              else for {
                contentCheck <- contentCheck(strongerCandidate)
                (successful, weaker) <- {
                  if (contentCheck) Future.successful((Some(strongerCandidate), weakerCandidates))
                  else findCandidate(weakerCandidates)
                }
              } yield (successful, weaker)
          }
      }
    }
  }

  private def migrate(currentReference: NormalizationReference, successfulCandidate: NormalizationCandidate, weakerCandidates: Seq[NormalizationCandidate]): Option[NormalizationReference] =
    db.readWrite(attempts = 2) { implicit session =>
      val latestCurrentUri = normalizedURIRepo.get(currentReference.uriId)
      val isWriteSafe =
        latestCurrentUri.state != NormalizedURIStates.INACTIVE &&
          latestCurrentUri.state != NormalizedURIStates.REDIRECTED &&
          latestCurrentUri.normalization == currentReference.persistedNormalization
      if (isWriteSafe) {
        val betterReference = internCandidate(successfulCandidate)
        val shouldMigrate = currentReference.uriId != betterReference.uriId
        if (shouldMigrate) {
          val correctNormalization = currentReference.normalization orElse {
            weakerCandidates.collectFirst { case candidate if candidate.isTrusted && candidate.url == currentReference.url => candidate.normalization }
          }
          if (currentReference.persistedNormalization != correctNormalization) {
            saveAndLog(latestCurrentUri.withNormalization(correctNormalization.get))
          }
        }
        log.info(s"Better reference ${betterReference.uriId}: ${betterReference.url} found for ${currentReference.uriId}: ${currentReference.url}")
        Some((betterReference, shouldMigrate))
      } else {
        log.warn(s"Aborting verified normalization because of recent overwrite of $currentReference with $latestCurrentUri")
        None
      }
    } map {
      case (betterReference, shouldMigrate) =>
        if (shouldMigrate) {
          uriIntegrityPlugin.handleChangedUri(URIMigration(oldUri = currentReference.uriId, newUri = betterReference.uriId))
          log.info(s"${currentReference.uriId}: ${currentReference.url} will be redirected to ${betterReference.uriId}: ${betterReference.url}")
        }
        betterReference
    }

  private def internCandidate(successfulCandidate: NormalizationCandidate)(implicit session: RWSession): NormalizationReference = {
    val (url, normalization) = (successfulCandidate.url, successfulCandidate.normalization)
    val newReferenceUri = normalizedURIRepo.getByNormalizedUrl(url) match {
      case None => saveAndLog(NormalizedURI.withHash(normalizedUrl = url, normalization = Some(normalization)))
      case Some(uri) if uri.normalization.isEmpty || uri.normalization.get < normalization => saveAndLog(uri.withNormalization(normalization))
      case Some(uri) => uri
    }
    NormalizationReference(newReferenceUri, isNew = true)
  }

  private def saveAndLog(uri: NormalizedURI)(implicit session: RWSession) =
    normalizedURIRepo.save(uri) tap { saved => log.debug(s"${saved.id.get}: ${saved.url} saved with ${saved.normalization.get}") }

  def processAdditionalUpdates(currentReference: NormalizationReference, betterReferenceOption: Option[NormalizationReference]): Future[Option[NormalizationReference]] = timing(s"NormalizationService.processAdditionalUpdates.${currentReference.url}") {
    val bestReference = betterReferenceOption getOrElse currentReference
    val newReferenceOption = Some(bestReference).filter(ref => ref.isNew && ref.normalization.isDefined)
    val additionalUpdatesOption = newReferenceOption.map { newReference =>
      val newReferenceCandidate = VerifiedCandidate(newReference.url, newReference.normalization.get)
      getURIsToBeFurtherUpdated(currentReference, newReference).map { uri =>
        processUpdate(NormalizationReference(uri), Set(newReferenceCandidate))
      }
    }

    additionalUpdatesOption match {
      case None => Future.successful(None)
      case Some(additionalUpdates) => Future.sequence(additionalUpdates).map(_ => betterReferenceOption)
    }
  }

  private def getURIsToBeFurtherUpdated(currentReference: NormalizationReference, newReference: NormalizationReference): Set[NormalizedURI] = db.readOnlyMaster { implicit session =>
    val haveBeenUpdated = Set(currentReference.url, newReference.url)
    val toBeUpdated = for {
      url <- haveBeenUpdated
      (_, normalizedURI) <- findVariations(url) if !normalizedURI.normalization.exists(_ > newReference.normalization.get)
      toBeUpdated <- (normalizedURI.state, normalizedURI.redirect) match {
        case (NormalizedURIStates.INACTIVE, _) => None
        case (NormalizedURIStates.REDIRECTED, Some(id)) =>
          val redirectionURI = normalizedURIRepo.get(id)
          if (redirectionURI.state != NormalizedURIStates.INACTIVE && !redirectionURI.normalization.exists(_ > newReference.normalization.get)) Some(redirectionURI) else None
        case (_, _) => Some(normalizedURI)
      }
    } yield toBeUpdated

    toBeUpdated.filter(uri => !haveBeenUpdated.contains(uri.url))
  }

  private def persistFailedAttempts(contentCheck: ContentCheck): Unit = {
    contentCheck.getFailedAttempts().foreach {
      case (url1, url2) => db.readWrite(attempts = 3) { implicit session =>
        failedContentCheckRepo.createOrIncrease(url1, url2)
      }
    }
  }
}
