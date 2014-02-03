package com.keepit.normalizer

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.net.URI
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.model._
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.integrity.{URIMigration, UriIntegrityPlugin}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import com.keepit.common.db.slick.Database
import com.keepit.common._
import com.keepit.common.db.Id

@ImplementedBy(classOf[NormalizationServiceImpl])
trait NormalizationService {
  def update(currentReference: NormalizationReference, candidates: NormalizationCandidate*): Future[Option[Id[NormalizedURI]]]
  def normalize(uriString: String)(implicit session: RSession): String
  def prenormalize(uriString: String)(implicit session: RSession): String
  def prenormalizeMaybe(uriString: String)(implicit session: RSession): Option[String]
}

@Singleton
class NormalizationServiceImpl @Inject() (
  db: Database,
  failedContentCheckRepo: FailedContentCheckRepo,
  normalizedURIRepo: NormalizedURIRepo,
  uriIntegrityPlugin: UriIntegrityPlugin,
  priorKnowledge: PriorKnowledge,
  airbrake: AirbrakeNotifier) extends NormalizationService with Logging {

  def normalize(uriString: String)(implicit session: RSession): String = normalizedURIRepo.getByUri(uriString).map(_.url) getOrElse prenormalize(uriString)
  def prenormalize(uriString: String)(implicit session: RSession): String = prenormalizeMaybe(uriString) getOrElse uriString

    //using readonly db when exist, don't use cache
  def prenormalizeMaybe(uriString: String)(implicit session: RSession): Option[String] = {
    val withStandardPrenormalizationOption = URI.safelyParse(uriString).map(Prenormalizer)
    val withPreferredSchemeOption = for {
      prenormalized <- withStandardPrenormalizationOption
      schemeNormalizer <- priorKnowledge.getPreferredSchemeNormalizer(uriString)
    } yield schemeNormalizer(prenormalized)

    val prenormalizedURIOption = withPreferredSchemeOption orElse withStandardPrenormalizationOption
    prenormalizedURIOption.map(_.toString())
  }

  def update(currentReference: NormalizationReference, candidates: NormalizationCandidate*): Future[Option[Id[NormalizedURI]]] = {
    val relevantCandidates = getRelevantCandidates(currentReference, candidates)
    for {
      betterReferenceOption <- processUpdate(currentReference, relevantCandidates: _*)
      betterReferenceOptionAfterAdditionalUpdates <- processAdditionalUpdates(currentReference, betterReferenceOption)
    } yield betterReferenceOptionAfterAdditionalUpdates.map(_.uriId)
  } tap(_.onFailure {
    case e => airbrake.notify(s"Normalization update failed", e)
  })

  private def processUpdate(currentReference: NormalizationReference, candidates: NormalizationCandidate*): Future[Option[NormalizationReference]] = {
    val contentChecks = db.readOnly { implicit session => priorKnowledge.getContentChecks(currentReference.url, currentReference.signature) }
    val findStrongerCandidate = FindStrongerCandidate(currentReference, Action(currentReference, contentChecks))

    for { (successfulCandidateOption, weakerCandidates) <- findStrongerCandidate(candidates) } yield {
      contentChecks.foreach(persistFailedAttempts(_))
      for {
        successfulCandidate <- successfulCandidateOption
        betterReference <- migrate(currentReference, successfulCandidate, weakerCandidates)
      } yield betterReference
    }
  }

  private def getRelevantCandidates(currentReference: NormalizationReference, candidates: Seq[NormalizationCandidate]) = {

    val prenormalizedCandidates = candidates.flatMap {
      case verifiedCandidate: VerifiedCandidate => Some(verifiedCandidate)
      case ScrapedCandidate(url, normalization) => db.readOnly { implicit session => prenormalizeMaybe(url).map(ScrapedCandidate(_, normalization)) }
      case UntrustedCandidate(url, normalization) => db.readOnly { implicit session => prenormalizeMaybe(url).map(UntrustedCandidate(_, normalization)) }
    }

    val allCandidates =
      if (currentReference.isNew || currentReference.normalization.isEmpty)
        prenormalizedCandidates ++ findVariations(currentReference.url).map { case (normalization, uri) => VerifiedCandidate(uri.url, uri.normalization.getOrElse(normalization)) }
      else
        prenormalizedCandidates

    allCandidates.filter(isRelevant(currentReference, _))
  }

  private def findVariations(referenceUrl: String): Seq[(Normalization, NormalizedURI)] = db.readOnly { implicit session =>
    for {
      (normalization, urlVariation) <- SchemeNormalizer.generateVariations(referenceUrl)
      uri <- normalizedURIRepo.getByNormalizedUrl(urlVariation)
    } yield (normalization, uri)
  }

  private def isRelevant(currentReference: NormalizationReference, candidate: NormalizationCandidate): Boolean = {
    currentReference.normalization.isEmpty ||
    currentReference.normalization.get < candidate.normalization ||
    (currentReference.normalization.get == candidate.normalization && currentReference.url != candidate.url)
  }

  private case class FindStrongerCandidate(currentReference: NormalizationReference, oracle: NormalizationCandidate => Action) {

    def apply(candidates: Seq[NormalizationCandidate]): Future[(Option[NormalizationCandidate], Seq[NormalizationCandidate])] =
      findCandidate(candidates.sortBy(_.normalization).reverse)

    def findCandidate(orderedCandidates: Seq[NormalizationCandidate]): Future[(Option[NormalizationCandidate], Seq[NormalizationCandidate])] = {
      orderedCandidates match {
        case Seq() => Future.successful((None, Seq()))
        case Seq(strongerCandidate, weakerCandidates @ _*) => {
          assert(weakerCandidates.isEmpty || weakerCandidates.head.normalization <= strongerCandidate.normalization, s"Normalization candidates ${weakerCandidates.head} and $strongerCandidate have not been sorted properly for ${currentReference}")
          assert(currentReference.normalization.isEmpty || currentReference.normalization.get <= strongerCandidate.normalization, s"Normalization candidate $strongerCandidate has not been filtered properly for $currentReference")

          db.readOnly { implicit session =>
            oracle(strongerCandidate) match {
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
    }
  }

  private def migrate(currentReference: NormalizationReference, successfulCandidate: NormalizationCandidate, weakerCandidates: Seq[NormalizationCandidate]): Option[NormalizationReference] =
    db.readWrite { implicit session =>
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
          if (currentReference.persistedNormalization != correctNormalization)
            saveAndLog(latestCurrentUri.withNormalization(correctNormalization.get))

          uriIntegrityPlugin.handleChangedUri(URIMigration(oldUri = currentReference.uriId, newUri = betterReference.uriId))
          log.info(s"${currentReference.uriId}: ${currentReference.url} will be redirected to ${betterReference.uriId}: ${betterReference.url}")
        }
        Some(betterReference)
      }
      else None
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
    normalizedURIRepo.save(uri) tap { saved => log.info(s"${saved.id.get}: ${saved.url} saved with ${saved.normalization.get}") }

  def processAdditionalUpdates(currentReference: NormalizationReference, betterReferenceOption: Option[NormalizationReference]): Future[Option[NormalizationReference]] = {
    val bestReference = betterReferenceOption getOrElse currentReference
    val newReferenceOption = Some(bestReference).filter(ref => ref.isNew && ref.normalization.isDefined)
    val additionalUpdatesOption = newReferenceOption.map { newReference =>
      val newReferenceCandidate = VerifiedCandidate(newReference.url, newReference.normalization.get)
      getURIsToBeFurtherUpdated(currentReference, newReference).map { uri =>
        processUpdate(NormalizationReference(uri), newReferenceCandidate)
      }
    }

    additionalUpdatesOption match {
      case None => Future.successful(None)
      case Some(additionalUpdates) => Future.sequence(additionalUpdates).map(_ => betterReferenceOption)
    }
  }

  private def getURIsToBeFurtherUpdated(currentReference: NormalizationReference, newReference: NormalizationReference): Set[NormalizedURI] = db.readOnly { implicit session =>
    val haveBeenUpdated = Set(currentReference.url, newReference.url)
    val toBeUpdated = for {
      url <- haveBeenUpdated
      (_, normalizedURI) <- findVariations(url) if normalizedURI.normalization.isEmpty || normalizedURI.normalization.get <= newReference.normalization.get
      toBeUpdated <- (normalizedURI.state, normalizedURI.redirect) match {
        case (NormalizedURIStates.INACTIVE, _) => None
        case (NormalizedURIStates.REDIRECTED, Some(id)) => {
          val redirectionURI = normalizedURIRepo.get(id)
          if (redirectionURI.state != NormalizedURIStates.INACTIVE && redirectionURI.normalization.get <= newReference.normalization.get) Some(redirectionURI) else None
        }
        case (_, _) => Some(normalizedURI)
      }
    } yield toBeUpdated

    toBeUpdated.filter(uri => !haveBeenUpdated.contains(uri.url))
  }

  private def persistFailedAttempts(contentCheck: ContentCheck): Unit = {
    contentCheck.getFailedAttempts().foreach { case (url1, url2) => db.readWrite { implicit session => failedContentCheckRepo.createOrIncrease(url1, url2) }}
  }
}
