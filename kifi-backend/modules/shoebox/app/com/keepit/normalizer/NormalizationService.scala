package com.keepit.normalizer

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.net.URI
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.model._
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.integrity.{URIMigration, UriIntegrityPlugin}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import com.keepit.common.db.slick.Database
import com.keepit.common._
import com.keepit.common.db.Id

@ImplementedBy(classOf[NormalizationServiceImpl])
trait NormalizationService {
  def update(current: NormalizedURI, candidates: NormalizationCandidate*): Future[Option[Id[NormalizedURI]]] = update(current, false, candidates)
  def update(current: NormalizedURI, isNew: Boolean, candidates: Seq[NormalizationCandidate]): Future[Option[Id[NormalizedURI]]]
  def normalize(uriString: String)(implicit session: RSession): String
  def prenormalize(uriString: String)(implicit session: RSession): String
}

@Singleton
class NormalizationServiceImpl @Inject() (
  db: Database,
  failedContentCheckRepo: FailedContentCheckRepo,
  normalizedURIRepo: NormalizedURIRepo,
  uriIntegrityPlugin: UriIntegrityPlugin,
  priorKnowledge: PriorKnowledge,
  airbrake: AirbrakeNotifier) extends NormalizationService with Logging {

  def normalize(uriString: String)(implicit session: RSession): String = normalizedURIRepo.getByUri(uriString).map(_.url).getOrElse(prenormalize(uriString))

  //using readonly db when exist, don't use cache
  def prenormalize(uriString: String)(implicit session: RSession): String = {
    val withStandardPrenormalizationOption = URI.safelyParse(uriString).map(Prenormalizer)
    val withPreferredSchemeOption = for {
      prenormalized <- withStandardPrenormalizationOption
      schemeNormalizer <- priorKnowledge.getPreferredSchemeNormalizer(uriString)
    } yield schemeNormalizer(prenormalized)

    val prenormalizedStringOption = for {
      prenormalizedURI <- withPreferredSchemeOption orElse withStandardPrenormalizationOption
      prenormalizedString <- prenormalizedURI.safelyToString()
    } yield prenormalizedString

    prenormalizedStringOption.getOrElse(uriString)
  }

  def update(currentReference: NormalizedURI, isNew: Boolean, candidates: Seq[NormalizationCandidate]): Future[Option[Id[NormalizedURI]]] = {
    val relevantCandidates = getRelevantCandidates(currentReference, isNew, candidates)
    for {
      betterReferenceOption <- processUpdate(currentReference, relevantCandidates: _*)
      betterReferenceOptionAfterAdditionalUpdates <- processAdditionalUpdates(currentReference, isNew, betterReferenceOption)
    } yield betterReferenceOptionAfterAdditionalUpdates.map(_.id.get)
  } tap(_.onFailure {
    case e => airbrake.notify(s"Normalization update failed", e)
  })

  private def processUpdate(currentReference: NormalizedURI, candidates: NormalizationCandidate*): Future[Option[NormalizedURI]] = {

    val contentChecks = db.readOnly { implicit session => priorKnowledge.getContentChecks(currentReference.url) }
    val findStrongerCandidate = FindStrongerCandidate(currentReference, Action(currentReference, contentChecks))

    for { (successfulCandidateOption, weakerCandidates) <- findStrongerCandidate(candidates) } yield {
      contentChecks.foreach(persistFailedAttempts(_))
      for {
        successfulCandidate <- successfulCandidateOption
        betterReference <- migrate(currentReference, successfulCandidate, weakerCandidates)
      } yield betterReference
    }
  }

  private def getRelevantCandidates(currentReference: NormalizedURI, isNew: Boolean, candidates: Seq[NormalizationCandidate]) = {

    val prenormalizedCandidates = candidates.map {
      case UntrustedCandidate(url, normalization) => db.readOnly { implicit session => UntrustedCandidate(prenormalize(url), normalization) }
      case candidate: TrustedCandidate => candidate
    }

    val allCandidates =
      if (isNew || currentReference.normalization.isEmpty)
        prenormalizedCandidates ++ findVariations(currentReference.url).map { case (normalization, uri) => TrustedCandidate(uri.url, uri.normalization.getOrElse(normalization)) }
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

  private def isRelevant(currentReference: NormalizedURI, candidate: NormalizationCandidate): Boolean =
    currentReference.normalization.isEmpty ||
    currentReference.normalization.get < candidate.normalization ||
    (currentReference.normalization.get == candidate.normalization && currentReference.url != candidate.url)

  private case class FindStrongerCandidate(currentReference: NormalizedURI, oracle: NormalizationCandidate => Action) {

    def apply(candidates: Seq[NormalizationCandidate]): Future[(Option[NormalizationCandidate], Seq[NormalizationCandidate])] =
      findCandidate(candidates.sortBy(_.normalization).reverse)

    def findCandidate(orderedCandidates: Seq[NormalizationCandidate]): Future[(Option[NormalizationCandidate], Seq[NormalizationCandidate])] = {
      orderedCandidates match {
        case Seq() => Future.successful((None, Seq()))
        case Seq(strongerCandidate, weakerCandidates @ _*) => {
          assert(weakerCandidates.isEmpty || weakerCandidates.head.normalization <= strongerCandidate.normalization, "Normalization candidates have not been sorted properly")
          assert(currentReference.normalization.isEmpty || currentReference.normalization.get <= strongerCandidate.normalization, "Normalization candidates have not been filtered properly")

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

  private def migrate(currentReference: NormalizedURI, successfulCandidate: NormalizationCandidate, weakerCandidates: Seq[NormalizationCandidate]): Option[NormalizedURI] =
    db.readWrite { implicit session =>
      val latestCurrent = normalizedURIRepo.get(currentReference.id.get)
      if (latestCurrent.state != NormalizedURIStates.INACTIVE && latestCurrent.state != NormalizedURIStates.REDIRECTED && latestCurrent.normalization == currentReference.normalization) {
        val betterReference = internCandidate(successfulCandidate)

        val (oldUriId, newUriId) = (currentReference.id.get, betterReference.id.get)
        if (oldUriId != newUriId) {
          if (currentReference.normalization.isEmpty) {
            for (weakerVariationCandidate <- weakerCandidates.find { candidate => candidate.isTrusted && candidate.url == currentReference.url }) yield {
              saveAndLog(latestCurrent.withNormalization(weakerVariationCandidate.normalization))
            }
          }
          uriIntegrityPlugin.handleChangedUri(URIMigration(oldUri = oldUriId, newUri = newUriId))
          log.info(s"${oldUriId}: ${currentReference.url} will be redirected to ${newUriId}: ${betterReference.url}")
        }
        Some(betterReference)
      }
      else None
    }

  private def internCandidate(successfulCandidate: NormalizationCandidate)(implicit session: RWSession): NormalizedURI = {
    val (url, normalization) = (successfulCandidate.url, successfulCandidate.normalization)
    normalizedURIRepo.getByNormalizedUrl(url) match {
      case None => saveAndLog(NormalizedURI.withHash(normalizedUrl = url, normalization = Some(normalization)))
      case Some(uri) if uri.normalization.isEmpty || uri.normalization.get < normalization => saveAndLog(uri.withNormalization(normalization))
      case Some(uri) => uri
    }
  }

  private def saveAndLog(uri: NormalizedURI)(implicit session: RWSession) =
    normalizedURIRepo.save(uri) tap { saved => log.info(s"${saved.id.get}: ${saved.url} saved with ${saved.normalization.get}") }

  def processAdditionalUpdates(currentReference: NormalizedURI, isNew: Boolean, betterReferenceOption: Option[NormalizedURI]): Future[Option[NormalizedURI]] = {
    val newReferenceOption = betterReferenceOption orElse (if (isNew) Some(currentReference) else None)
    val additionalUpdatesOption = newReferenceOption.map { newReference =>
      val betterReferenceCandidate = TrustedCandidate(newReference.url, newReference.normalization.get)
      getURIsToBeFurtherUpdated(currentReference, newReference).map { uri =>
        processUpdate(uri, betterReferenceCandidate)
      }
    }

    additionalUpdatesOption match {
      case None => Future.successful(None)
      case Some(additionalUpdates) => Future.sequence(additionalUpdates).map(_ => betterReferenceOption)
    }
  }

  private def getURIsToBeFurtherUpdated(currentReference: NormalizedURI, newReference: NormalizedURI): Set[NormalizedURI] = db.readOnly { implicit session =>
    val haveBeenUpdated = Set(currentReference.url, newReference.url)
    val toBeUpdated = for {
      url <- haveBeenUpdated
      (_, normalizedURI) <- findVariations(url) if normalizedURI.normalization.isEmpty || normalizedURI.normalization.get <= newReference.normalization.get
      toBeUpdated <- (normalizedURI.state, normalizedURI.redirect) match {
        case (NormalizedURIStates.INACTIVE, _) => None
        case (NormalizedURIStates.REDIRECTED, Some(id)) => {
          val redirectionURI = normalizedURIRepo.get(id)
          if (redirectionURI.state != NormalizedURIStates.INACTIVE) Some(redirectionURI) else None
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
