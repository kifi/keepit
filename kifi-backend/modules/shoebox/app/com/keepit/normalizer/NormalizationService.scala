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
  def update(current: NormalizedURI, candidates: NormalizationCandidate*)(implicit normalizedURIRepo: NormalizedURIRepo): Future[Option[NormalizedURI]]
  def normalize(uriString: String)(implicit session: RSession): String
}

@Singleton
class NormalizationServiceImpl @Inject() (
  db: Database,
  normalizationRuleRepo: UriNormalizationRuleRepo,
  failedContentCheckRepo: FailedContentCheckRepo,
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

  def update(current: NormalizedURI, candidates: NormalizationCandidate*)(implicit normalizedURIRepo: NormalizedURIRepo): Future[Option[NormalizedURI]] = {

    val validCandidates = current.normalization match {
      case Some(currentNormalization) => candidates.filter(_.normalization >= currentNormalization)
      case None => candidates ++ buildInternalCandidates(current.url)
    }

    val relevantCandidates = validCandidates.groupBy(_.url).map { case (url, candidates) => candidates.maxBy(_.normalization) }.toSet

    val findStrongerReference = FindStrongerReference(current)

    findStrongerReference(relevantCandidates).map { case (newReference, urlsToBeMigrated, urlsThatFailedContentCheck) => {
      db.readWrite { implicit session =>
        urlsThatFailedContentCheck.foreach(failedContentCheckRepo.createOrIncrease(_, current.url))
        newReference.map(migrate(_, urlsToBeMigrated))
      }
    }} tap(_.onFailure { case e => healthcheckPlugin.addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some(s"Normalization update failed: ${e.getMessage}"))) })
  }

  private def buildInternalCandidates(referenceUrl: String): Seq[NormalizationCandidate] = {
    val internalCandidatesTry =
      for {
        currentURI <- URI.parse(referenceUrl)
        internalCandidates <- Try {
          for (normalization <- Normalization.priority.keys if normalization <= Normalization.HTTPS) yield {
            val candidateUrl = SchemeNormalizer(normalization)(currentURI).toString()
            NormalizationCandidate(candidateUrl, normalization)
          }
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

  private case class ContentCheck(referenceUrl: String) {
    lazy val referenceContentSignatureFuture = scraperPlugin.asyncSignature(referenceUrl)
    def apply(alternateUrl: String)(implicit session: RSession): Future[Boolean] = {
      if (failedContentCheckRepo.contains(referenceUrl, alternateUrl))
        Future.successful(false)
      else for {
        currentContentSignatureOption <- referenceContentSignatureFuture
        candidateContentSignatureOption <- scraperPlugin.asyncSignature(alternateUrl) if currentContentSignatureOption.isDefined
      } yield (currentContentSignatureOption, candidateContentSignatureOption) match {
          case (Some(currentContentSignature), Some(candidateContentSignature)) => currentContentSignature.similarTo(candidateContentSignature) > 0.9
          case _ => {
            healthcheckPlugin.addError(HealthcheckError(None, None, None, Healthcheck.INTERNAL, Some(s"Content signatures of URLs ${referenceUrl} and ${alternateUrl} could not be compared.")))
            false
          }
        }
    }
  }

  private case class FindStrongerReference(currentReference: NormalizedURI)(implicit normalizedURIRepo: NormalizedURIRepo) {
    val contentCheck = ContentCheck(currentReference.url)

    def apply(candidates: Set[NormalizationCandidate]): Future[(Option[NormalizedURI], Set[String], Set[String])] =
      processRecursively(candidates.toList.sortBy(_.normalization).reverse, Set.empty)

    def processRecursively(orderedCandidates: List[NormalizationCandidate], failedContentChecks: Set[String]): Future[(Option[NormalizedURI], Set[String], Set[String])] =
      db.readOnly() { implicit session =>
        orderedCandidates match {
          case Nil => Future.successful((None, Set.empty, failedContentChecks))
          case strongerCandidate::weakerCandidates => {
            assert(currentReference.normalization.isEmpty || currentReference.normalization.get <= strongerCandidate.normalization)
            assert(weakerCandidates.isEmpty || weakerCandidates.head.normalization <= strongerCandidate.normalization)

            val strongerCandidateUrl = normalize(strongerCandidate.url)

            if (currentReference.url == strongerCandidateUrl) {
              if (currentReference.normalization == strongerCandidate.normalization) Future.successful((None, Set.empty, failedContentChecks))
              else {
                val currentReferenceWithUpgradedNormalization = currentReference.withNormalization(strongerCandidate.normalization)
                val toBeMigrated = weakerCandidates.takeWhile(currentReference.normalization.isEmpty || _.normalization >= currentReference.normalization.get).map(_.url).toSet
                Future.successful((Some(currentReferenceWithUpgradedNormalization), toBeMigrated, failedContentChecks))
              }
            }

            else for {
              contentCheck <- contentCheck(strongerCandidateUrl)
              (newReference, urlsToBeMigrated, urlsThatFailedContentCheck) <- {
                if (contentCheck) db.readOnly() { implicit session =>
                  val candidateNormalizedURI = normalizedURIRepo.getByUri(strongerCandidateUrl) match {
                    case None => NormalizedURI.withHash(normalizedUrl = strongerCandidateUrl, normalization = Some(strongerCandidate.normalization))
                    case Some(uri) =>
                      if (uri.normalization.isEmpty || uri.normalization.get < strongerCandidate.normalization)
                        uri.withNormalization(strongerCandidate.normalization)
                      else uri
                  }
                  val toBeMigrated = Set(currentReference.url) ++ weakerCandidates.takeWhile(currentReference.normalization.isEmpty || _.normalization >= currentReference.normalization.get).map(_.url)

                  Future.successful((Some(candidateNormalizedURI), toBeMigrated, failedContentChecks))
                }
                else apply(weakerCandidates, failedContentChecks + strongerCandidateUrl)
              }
            } yield (newReference, urlsToBeMigrated, urlsThatFailedContentCheck)
          }
        }
      }
  }

  private def migrate(referenceURI: NormalizedURI, urlsToBeMigrated: Set[String])(implicit normalizedURIRepo: NormalizedURIRepo, session: RWSession): NormalizedURI = {
    val savedReference = normalizedURIRepo.save(referenceURI)
    val mergedUris = for {
      url <- urlsToBeMigrated
      normalizedURI <- normalizedURIRepo.getByUri(url)
    } yield MergedUri(oldUri = normalizedURI.id.get, newUri = savedReference.id.get)

    mergedUris.foreach(uriIntegrityPlugin.handleChangedUri(_))
    savedReference
  }
}
