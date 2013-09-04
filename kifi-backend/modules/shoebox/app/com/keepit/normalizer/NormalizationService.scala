package com.keepit.normalizer

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.net.URI
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.model._
import com.keepit.common.logging.Logging
import com.keepit.scraper.ScraperPlugin
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin}
import scala.util.{Try, Failure}
import com.keepit.common.healthcheck.HealthcheckError
import scala.util.Success
import com.keepit.integrity.{MergedUri, UriIntegrityPlugin}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import com.keepit.common.db.slick.Database
import com.keepit.common._
import com.keepit.common.db.Id

@ImplementedBy(classOf[NormalizationServiceImpl])
trait NormalizationService {
  def update(current: NormalizedURI, candidates: NormalizationCandidate*): Future[Option[Id[NormalizedURI]]]
  def normalize(uriString: String)(implicit session: RSession): String
}

@Singleton
class NormalizationServiceImpl @Inject() (
  db: Database,
  failedContentCheckRepo: FailedContentCheckRepo,
  normalizedURIRepo: NormalizedURIRepo,
  uriIntegrityPlugin: UriIntegrityPlugin,
  scraperPlugin: ScraperPlugin,
  healthcheckPlugin: HealthcheckPlugin) extends NormalizationService with Logging {

  def normalize(uriString: String)(implicit session: RSession): String = normalizedURIRepo.getByUri(uriString).map(_.url).getOrElse(Prenormalizer(uriString))

  def update(current: NormalizedURI, candidates: NormalizationCandidate*): Future[Option[Id[NormalizedURI]]] = {

    implicit val uriRepo: NormalizedURIRepo = normalizedURIRepo
    implicit val scraper: ScraperPlugin = scraperPlugin

    val relevantCandidates = getRelevantCandidates(current, candidates)
    val priorKnowledge = PriorKnowledge(current)
    val findStrongerCandidate = FindStrongerCandidate(current, priorKnowledge)

    for {
      (successfulCandidateOption, weakerCandidates) <- findStrongerCandidate(relevantCandidates)
      newReferenceOption <- {

        priorKnowledge.contentChecks.foreach(persistFailedAttempts(_))

        val newReferenceWithRecursiveUpdatesOption = for {
          successfulCandidate <- successfulCandidateOption
          newReference <- migrate(current, successfulCandidate, weakerCandidates)
        } yield (newReference, getURIsToBeFurtherUpdated(current, newReference).map { uri =>
            update(uri, TrustedCandidate(newReference.url, newReference.normalization.get))
          })

        newReferenceWithRecursiveUpdatesOption match {
          case None => Future.successful(None)
          case Some((newReference, recursiveUpdates)) => Future.sequence(recursiveUpdates).map(_ => Some(newReference.id.get))
        }
      }
    } yield newReferenceOption
  } tap(_.onFailure { case e => healthcheckPlugin.addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some(s"Normalization update failed: ${e.getMessage}"))) })

  private def getRelevantCandidates(current: NormalizedURI, candidates: Seq[NormalizationCandidate]) = {

    val prenormalizedCandidates = candidates.map {
      case UntrustedCandidate(url, normalization) => UntrustedCandidate(Prenormalizer(url), normalization)
      case candidate: TrustedCandidate => candidate
    }

    current.normalization match {
      case Some(currentNormalization) => prenormalizedCandidates.filter(candidate => candidate.normalization > currentNormalization || (candidate.normalization == currentNormalization && candidate.url != current.url))
      case None => prenormalizedCandidates ++ findVariations(current.url).map { case (normalization, uri) => TrustedCandidate(uri.url, uri.normalization.getOrElse(normalization)) }
    }
  }

  private def findVariations(referenceUrl: String): Seq[(Normalization, NormalizedURI)] = {

    val variationsTry =
      for {
        referenceURI <- URI.parse(referenceUrl)
        variations <- Try {
          for {
            normalization <- Normalization.priority.keys if normalization <= Normalization.HTTPS
            urlVariation <- SchemeNormalizer(normalization)(referenceURI).safelyToString()
            uri <- db.readOnly { implicit session => normalizedURIRepo.getByNormalizedUrl(urlVariation) }
          } yield (normalization, uri)
        }
      } yield variations

    variationsTry match {
      case Success(variations) => variations.toSeq
      case Failure(e) => {
        healthcheckPlugin.addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some(s"Url variations could not be generated: ${e.getMessage}")))
        Seq.empty
      }
    }
  }

  private case class FindStrongerCandidate(currentReference: NormalizedURI, priorKnowledge: PriorKnowledge) {

    def apply(candidates: Seq[NormalizationCandidate]): Future[(Option[NormalizationCandidate], Seq[NormalizationCandidate])] =
      findCandidate(candidates.sortBy(_.normalization).reverse)

    def findCandidate(orderedCandidates: Seq[NormalizationCandidate]): Future[(Option[NormalizationCandidate], Seq[NormalizationCandidate])] = {
      orderedCandidates match {
        case Seq() => Future.successful((None, Seq()))
        case Seq(strongerCandidate, weakerCandidates @ _*) => {
          assert(weakerCandidates.isEmpty || weakerCandidates.head.normalization <= strongerCandidate.normalization)
          assert(currentReference.normalization.isEmpty || currentReference.normalization.get <= strongerCandidate.normalization)
          if (currentReference.normalization == Some(strongerCandidate.normalization)) assert(currentReference.url != strongerCandidate.url)

          db.readOnly { implicit session =>
            priorKnowledge(strongerCandidate) match {
              case PriorKnowledge.ACCEPT => Future.successful((Some(strongerCandidate), weakerCandidates))
              case PriorKnowledge.REJECT => findCandidate(weakerCandidates)
              case PriorKnowledge.Check(contentCheck) =>
                if (currentReference.url == strongerCandidate.url) Future.successful(Some(strongerCandidate), weakerCandidates)
                else for {
                  contentCheck <- contentCheck(strongerCandidate)(session)
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

  private def getNewReference(successfulCandidate: NormalizationCandidate)(implicit session: RSession): NormalizedURI = {
    val (url, normalization) = (successfulCandidate.url, successfulCandidate.normalization)
    normalizedURIRepo.getByNormalizedUrl(url) match {
      case None => NormalizedURI.withHash(normalizedUrl = url, normalization = Some(normalization))
      case Some(uri) if uri.normalization.isEmpty || uri.normalization.get < normalization => uri.withNormalization(normalization)
      case Some(uri) => uri
    }
  }

  private def getURIsToBeFurtherUpdated(currentReference: NormalizedURI, newReference: NormalizedURI): Set[NormalizedURI] = db.readOnly { implicit session =>

    val toBeUpdated = for {
      url <- Set(currentReference.url, newReference.url)
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

    val toBeExcluded = Set(currentReference.url, newReference.url)
    toBeUpdated.filter(uri => !toBeExcluded.contains(uri.url))
  }

  private def migrate(currentReference: NormalizedURI, successfulCandidate: NormalizationCandidate, weakerCandidates: Seq[NormalizationCandidate]): Option[NormalizedURI] = db.readWrite { implicit session =>
    val latestCurrent = normalizedURIRepo.get(currentReference.id.get)
    if (latestCurrent.state != NormalizedURIStates.INACTIVE && latestCurrent.state != NormalizedURIStates.REDIRECTED && latestCurrent.normalization == currentReference.normalization) {
      val newReference = getNewReference(successfulCandidate)
      val saved = normalizedURIRepo.save(newReference)

      val (oldUriId, newUriId) = (currentReference.id.get, saved.id.get)
      if (oldUriId != newUriId) {

        if (currentReference.normalization.isEmpty) {
          for (weakerVariationCandidate <- weakerCandidates.find { candidate => candidate.isTrusted && candidate.url == currentReference.url }) yield
            normalizedURIRepo.save(latestCurrent.withNormalization(weakerVariationCandidate.normalization))
        }

        uriIntegrityPlugin.handleChangedUri(MergedUri(oldUri = oldUriId, newUri = newUriId))
      }

      Some(saved)
    }
    else None
  }

  private def persistFailedAttempts(contentCheck: ContentCheck): Unit = {
    contentCheck.getFailedAttempts().foreach { case (url1, url2) => db.readWrite { implicit session => failedContentCheckRepo.createOrIncrease(url1, url2) }}
  }
}
