package com.keepit.normalizer

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.net.URI
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
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
    require(candidates.isEmpty, "We currently do not accept external submissions.")

    val relevantCandidates = current.normalization match {
      case Some(currentNormalization) => candidates.filter(_.normalization >= currentNormalization)
      case None => candidates ++ buildInternalCandidates(current.url)
    }

    val findStrongerCandidate = FindStrongerCandidate(current)
    findStrongerCandidate(relevantCandidates).map { case (successfulCandidateOption, weakerCandidates) => {
      db.readWrite { implicit session =>
        findStrongerCandidate.contentCheck.failedContentChecks.foreach(failedContentCheckRepo.createOrIncrease(_, current.url))
        for {
          successfulCandidate <- successfulCandidateOption
          (newReference, urlsToBeMigrated) <- Some(getNewReference(current, successfulCandidate, weakerCandidates)) if normalizationHasNotChanged(current)
        } yield migrate(newReference, urlsToBeMigrated)
      }
    }} tap(_.onFailure { case e => healthcheckPlugin.addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some(s"Normalization update failed: ${e.getMessage}"))) })
  }

  private def buildInternalCandidates(referenceUrl: String): Seq[NormalizationCandidate] = {
    val internalCandidatesTry =
      for {
        currentURI <- URI.parse(referenceUrl)
        internalCandidates <- Try {
          for {
            normalization <- Normalization.priority.keys if normalization <= Normalization.HTTPS
            candidateUrl <- SchemeNormalizer(normalization)(currentURI).safelyToString()
          } yield NormalizationCandidate(candidateUrl, normalization)
        }
      } yield internalCandidates

    internalCandidatesTry match {
      case Success(internalCandidates) => internalCandidates.toSeq
      case Failure(e) => {
        healthcheckPlugin.addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some(s"Normalization candidates could not be generated internally: ${e.getMessage}")))
        Seq.empty[NormalizationCandidate]
      }
    }
  }

  private case class FindStrongerCandidate(currentReference: NormalizedURI) {
    val contentCheck = ContentCheck(currentReference.url)
    val priorKnowledge = PriorKnowledge(currentReference)

    def apply(candidates: Seq[NormalizationCandidate]): Future[(Option[NormalizationCandidate], Seq[NormalizationCandidate])] =
      findCandidate(candidates.sortBy(_.normalization).reverse)

    def findCandidate(orderedCandidates: Seq[NormalizationCandidate]): Future[(Option[NormalizationCandidate], Seq[NormalizationCandidate])] = {
      orderedCandidates match {
        case Seq() => Future.successful((None, Seq()))
        case Seq(strongerCandidate, weakerCandidates @ _*) => {
          assert(currentReference.normalization.isEmpty || currentReference.normalization.get <= strongerCandidate.normalization)
          assert(weakerCandidates.isEmpty || weakerCandidates.head.normalization <= strongerCandidate.normalization)

          db.readOnly { implicit session =>
            priorKnowledge(strongerCandidate) match {
              case Some(true) => Future.successful((Some(strongerCandidate), weakerCandidates))
              case Some(false) => findCandidate(weakerCandidates)
              case None => {
                if (currentReference.url == strongerCandidate.url) Future.successful {
                  if (currentReference.normalization != strongerCandidate.normalization) (Some(strongerCandidate), weakerCandidates)
                  else (None, weakerCandidates)
                }
                else for {
                  contentCheck <- contentCheck(strongerCandidate.url)
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
  }

  private case class PriorKnowledge(currentReference: NormalizedURI) {
    def apply(candidate: NormalizationCandidate)(implicit session: RSession): Option[Boolean] = candidate.normalization match {
      case Normalization.CANONICAL | Normalization.OPENGRAPH => Some(false) // Refuse all external candidates
      case _ => if (normalizedURIRepo.getByNormalizedUrl(candidate.url).isEmpty) Some(false) else None // Lazy renormalization
    }
  }

  private case class ContentCheck(referenceUrl: String) {
    lazy val referenceContentSignatureFuture = scraperPlugin.asyncSignature(referenceUrl)
    var failedContentChecks = Set.empty[String]
    var referenceUrlIsBroken = false

    def apply(alternateUrl: String)(implicit session: RSession): Future[Boolean] = {
      if (referenceUrlIsBroken || failedContentChecks.contains(alternateUrl) || failedContentCheckRepo.contains(referenceUrl, alternateUrl)) Future.successful(false)
      else for {
        currentContentSignatureOption <- referenceContentSignatureFuture
        candidateContentSignatureOption <- if (currentContentSignatureOption.isDefined) scraperPlugin.asyncSignature(alternateUrl) else Future.successful(None)
      } yield (currentContentSignatureOption, candidateContentSignatureOption) match {
          case (Some(currentContentSignature), Some(candidateContentSignature)) => currentContentSignature.similarTo(candidateContentSignature) > 0.9
          case (Some(_), None) => {
            healthcheckPlugin.addError(HealthcheckError(None, None, None, Healthcheck.INTERNAL, Some(s"Content signature of URL ${alternateUrl} could not be computed.")))
            failedContentChecks += alternateUrl ; false
          }
          case (None, _) => {
            healthcheckPlugin.addError(HealthcheckError(None, None, None, Healthcheck.INTERNAL, Some(s"Content signature of reference URL ${referenceUrl} could not be computed.")))
            referenceUrlIsBroken = true ; false
          }
        }
    }
  }

  private def getNewReference(currentReference: NormalizedURI, successfulCandidate: NormalizationCandidate, weakerCandidates: Seq[NormalizationCandidate])(implicit session: RSession): (NormalizedURI, Set[String]) = {
    val newReference = successfulCandidate match {
      case NormalizationCandidate(url, normalization) => normalizedURIRepo.getByNormalizedUrl(url) match {
        case None => NormalizedURI.withHash(normalizedUrl = url, normalization = Some(normalization))
        case Some(uri) => {
          val activeUri = if (uri.state == NormalizedURIStates.INACTIVE) uri.withState(NormalizedURIStates.ACTIVE) else uri
          if (activeUri.normalization.isEmpty || activeUri.normalization.get < normalization)
            activeUri.withNormalization(normalization)
          else activeUri
        }
      }
    }
    val toBeMigrated = Set(currentReference.url) - newReference.url ++ weakerCandidates.takeWhile(currentReference.normalization.isEmpty || _.normalization >= currentReference.normalization.get).map(_.url)
    (newReference, toBeMigrated)
  }

  private def normalizationHasNotChanged(currentReference: NormalizedURI)(implicit session: RSession) = {
    val mostRecent = normalizedURIRepo.get(currentReference.id.get)
    (mostRecent.state != NormalizedURIStates.INACTIVE) && (mostRecent.normalization == currentReference.normalization)
  }

  private def migrate(referenceURI: NormalizedURI, urlsToBeMigrated: Set[String])(implicit session: RWSession): NormalizedURI = {
    val savedReference = normalizedURIRepo.save(referenceURI)
    val mergedUris = for {
      url <- urlsToBeMigrated
      normalizedURI <- normalizedURIRepo.getByUri(url)
    } yield MergedUri(oldUri = normalizedURI.id.get, newUri = savedReference.id.get)

    mergedUris.foreach(uriIntegrityPlugin.handleChangedUri(_))
    savedReference
  }
}
