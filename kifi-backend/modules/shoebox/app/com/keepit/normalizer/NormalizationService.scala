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

@ImplementedBy(classOf[NormalizationServiceImpl])
trait NormalizationService {
  def update(current: NormalizedURI, candidates: NormalizationCandidate*): Future[Option[NormalizedURI]]
  def normalize(uriString: String)(implicit session: RSession): String
}

@Singleton
class NormalizationServiceImpl @Inject() (
  db: Database,
  normalizationRuleRepo: UriNormalizationRuleRepo,
  failedContentCheckRepo: FailedContentCheckRepo,
  normalizedURIRepo: NormalizedURIRepo,
  uriIntegrityPlugin: UriIntegrityPlugin,
  scraperPlugin: ScraperPlugin,
  healthcheckPlugin: HealthcheckPlugin) extends NormalizationService with Logging {

  def normalize(uriString: String)(implicit session: RSession): String = {
    val prepUrlTry = for {
      uri <- URI.parse(uriString)
      prepUrl <- Try { Prenormalizer(uri).toString() }
    } yield prepUrl

    prepUrlTry match {
      case Failure(e) => {
        healthcheckPlugin.addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some(s"Static Normalization failed: ${e.getMessage}")))
        uriString
      }
      case Success(prepUrl) => {
        val mappedUrl = normalizationRuleRepo.getByUrl(prepUrl)
        mappedUrl.getOrElse(prepUrl)
      }
    }
  }

  def update(current: NormalizedURI, candidates: NormalizationCandidate*): Future[Option[NormalizedURI]] = {

    implicit val uriRepo: NormalizedURIRepo = normalizedURIRepo
    implicit val scraper: ScraperPlugin = scraperPlugin

    val relevantCandidates = current.normalization match {
      case Some(currentNormalization) => candidates.filter(candidate => candidate.normalization > currentNormalization || (candidate.normalization == currentNormalization && candidate.url != current.url))
      case None => candidates ++ buildInternalCandidates(current.url)
    }

    val priorKnowledge = PriorKnowledge(current)
    val findStrongerCandidate = FindStrongerCandidate(current, priorKnowledge)
    for {
      (successfulCandidateOption, weakerCandidates) <- findStrongerCandidate(relevantCandidates)
      newReferenceOption <- {

        priorKnowledge.contentChecks.foreach(persistFailedAttempts(_))

        val newReferenceWithRecursiveUpdatesOption = for {
          successfulCandidate <- successfulCandidateOption
          newReference <- migrate(current, successfulCandidate)
        } yield (newReference, getURIsToBeFurtherUpdated(current,  newReference, weakerCandidates).map(update(_, successfulCandidate)))

        newReferenceWithRecursiveUpdatesOption match {
          case None => Future.successful(None)
          case Some((newReference, recursiveUpdates)) => Future.sequence(recursiveUpdates).map(_ => Some(newReference))
        }
      }
    } yield newReferenceOption
  } tap(_.onFailure { case e => healthcheckPlugin.addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some(s"Normalization update failed: ${e.getMessage}"))) })


  private def buildInternalCandidates(referenceUrl: String): Seq[NormalizationCandidate] = {

    val schemeCandidatesTry =
      for {
        currentURI <- URI.parse(referenceUrl)
        schemeCandidates <- Try {
          for {
            normalization <- Normalization.priority.keys if normalization <= Normalization.HTTPS
            candidateUrl <- SchemeNormalizer(normalization)(currentURI).safelyToString()
          } yield TrustedCandidate(candidateUrl, normalization)
        }
      } yield schemeCandidates

    schemeCandidatesTry match {
      case Success(schemeCandidates) => schemeCandidates.toSeq
      case Failure(e) => {
        healthcheckPlugin.addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some(s"Normalization candidates could not be generated internally: ${e.getMessage}")))
        Seq.empty[TrustedCandidate]
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
      case Some(uri) if uri.state == NormalizedURIStates.INACTIVE => uri.copy(state = NormalizedURIStates.ACTIVE, redirect = None, redirectTime = None, normalization = Some(normalization))
      case Some(uri) if uri.normalization.isEmpty || uri.normalization.get < normalization => uri.withNormalization(normalization)
      case Some(uri) => uri
    }
  }

  private def getURIsToBeFurtherUpdated(currentReference: NormalizedURI, newReference: NormalizedURI, weakerCandidates: Seq[NormalizationCandidate]): Set[NormalizedURI] = db.readOnly { implicit session =>
    val toBeUpdated = for {
      candidate <- weakerCandidates.takeWhile(currentReference.normalization.isEmpty || _.normalization >= currentReference.normalization.get).toSet[NormalizationCandidate]
      normalizedURI <- normalizedURIRepo.getByUri(candidate.url)
      toBeUpdated <- (normalizedURI.state, normalizedURI.redirect) match {
        case (NormalizedURIStates.INACTIVE, None) => None
        case (NormalizedURIStates.INACTIVE, Some(id)) => {
          val redirectionURI = normalizedURIRepo.get(id)
          if (redirectionURI.state != NormalizedURIStates.INACTIVE) Some(redirectionURI) else None
        }
        case (_, _) => Some(normalizedURI)
      }
    } yield toBeUpdated

    val toBeExcluded = Set(currentReference.url, newReference.url)
    toBeUpdated.filter(uri => !toBeExcluded.contains(uri.url))
  }

  private def migrate(currentReference: NormalizedURI, successfulCandidate: NormalizationCandidate): Option[NormalizedURI] = db.readWrite { implicit session =>
    val latestCurrent = normalizedURIRepo.get(currentReference.id.get)
    if (latestCurrent.state != NormalizedURIStates.INACTIVE && latestCurrent.normalization == currentReference.normalization) {
      val newReference = getNewReference(successfulCandidate)
      val saved = normalizedURIRepo.save(newReference)

      val (oldUriId, newUriId) = (currentReference.id.get, saved.id.get)
      if (oldUriId != newUriId) uriIntegrityPlugin.handleChangedUri(MergedUri(oldUri = oldUriId, newUri = newUriId))

      Some(saved)
    }
    else None
  }

  private def persistFailedAttempts(contentCheck: ContentCheck): Unit = {
    contentCheck.getFailedAttempts().foreach { case (url1, url2) => db.readWrite { implicit session => failedContentCheckRepo.createOrIncrease(url1, url2) }}
  }
}
