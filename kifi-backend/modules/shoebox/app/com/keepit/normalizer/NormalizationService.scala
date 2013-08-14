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

@ImplementedBy(classOf[NormalizationServiceImpl])
trait NormalizationService {
  def update(current: NormalizedURI, candidates: NormalizationCandidate*)(implicit normalizedURIRepo: NormalizedURIRepo, session: RWSession): Future[Option[NormalizedURI]]
  def normalize(uriString: String)(implicit session: RSession): String
}

@Singleton
class NormalizationServiceImpl @Inject() (
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

  def update(current: NormalizedURI, candidates: NormalizationCandidate*)(implicit normalizedURIRepo: NormalizedURIRepo, session: RWSession): Future[Option[NormalizedURI]] = {

    // Inner method that builds internal candidates from alternate schemes/hosts [NO DB]
    def buildInternalCandidates(): Seq[NormalizationCandidate] = {
      val internalCandidatesTry =
        for {
          currentURI <- URI.parse(current.url)
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

    // Inner method that content-checks a candidate url versus the current one, for which the signature is cached in closure [DB READONLY]
    lazy val currentContentSignatureFuture = scraperPlugin.asyncSignature(current.url)
    def checkSignature(normalizedCandidateUrl: String): Future[Boolean] = {
      if (failedContentCheckRepo.contains(current.url, normalizedCandidateUrl))
        Future.successful(false)
      else for {
        currentContentSignatureOption <- currentContentSignatureFuture
        candidateContentSignatureOption <- scraperPlugin.asyncSignature(normalizedCandidateUrl) if currentContentSignatureOption.isDefined
      } yield (currentContentSignatureOption, candidateContentSignatureOption) match {
          case (Some(currentContentSignature), Some(candidateContentSignature)) => currentContentSignature.similarTo(candidateContentSignature) > 0.9
          case _ => {
            healthcheckPlugin.addError(HealthcheckError(None, None, None, Healthcheck.INTERNAL, Some(s"Content signatures of URLs ${current.url} and ${normalizedCandidateUrl} could not be compared.")))
            false
          }
        }
    }

    // Recursive inner method that tries to find a matching normalization and splits candidates into migration candidate urls and failed candidate urls [DB READONLY]
    def findStrongerMatch(orderedCandidates: List[NormalizationCandidate], failedContentChecks: List[String]): Future[(Option[NormalizedURI], Seq[String], Seq[String])] = orderedCandidates match {
      case Nil => Future.successful((None, Nil, failedContentChecks))
      case strongerCandidate::weakerCandidates => {
        assert(current.normalization.isEmpty || current.normalization.get <= strongerCandidate.normalization)
        assert(weakerCandidates.isEmpty || weakerCandidates.head.normalization <= strongerCandidate.normalization)

        val strongerCandidateUrl = normalize(strongerCandidate.url)

        if (current.url == strongerCandidateUrl) {
          if (current.normalization == strongerCandidate.normalization) Future.successful((None, Nil, failedContentChecks))
          else {
            val currentWithUpgradedNormalization = current.withNormalization(strongerCandidate.normalization)
            val toBeMigrated = weakerCandidates.takeWhile(current.normalization.isEmpty || _.normalization >= current.normalization.get).map(_.url)
            Future.successful((Some(currentWithUpgradedNormalization), toBeMigrated, failedContentChecks))
          }
        }

        else for {
          contentCheck <- checkSignature(strongerCandidateUrl)
          (authoritativeURI, urlsToBeMigrated, urlsThatFailedContentCheck) <- {
            if (contentCheck) {
              val candidateNormalizedURI = normalizedURIRepo.getByUri(strongerCandidateUrl) match {
                case None => NormalizedURI.withHash(normalizedUrl = strongerCandidateUrl, normalization = Some(strongerCandidate.normalization))
                case Some(uri) =>
                  if (uri.normalization.isEmpty || uri.normalization.get < strongerCandidate.normalization)
                    uri.withNormalization(strongerCandidate.normalization)
                  else uri
              }
              val toBeMigrated = current.url :: weakerCandidates.takeWhile(current.normalization.isEmpty || _.normalization >= current.normalization.get).map(_.url)

              Future.successful((Some(candidateNormalizedURI), toBeMigrated, failedContentChecks))
            }
            else findStrongerMatch(weakerCandidates, strongerCandidateUrl::failedContentChecks)
          }
        } yield (authoritativeURI, urlsToBeMigrated, urlsThatFailedContentCheck)
      }
    }

    // Inner method that triggers the necessary migrations [DB READWRITE]
    def migrate(authoritativeURI: NormalizedURI, urlsToBeMigrated: Seq[String]): NormalizedURI = {
      normalizedURIRepo.save(authoritativeURI)
      val mergedUris = for {
        url <- urlsToBeMigrated
        normalizedURI <- normalizedURIRepo.getByUri(url)
      } yield MergedUri(oldUri = normalizedURI.id.get, newUri = authoritativeURI.id.get)

      mergedUris.foreach(uriIntegrityPlugin.handleChangedUri(_))
      authoritativeURI
    }

    // Main routine [DB READWRITE]

    val allCandidates = current.normalization match {
      case Some(currentNormalization) => candidates.filter(_.normalization >= currentNormalization)
      case None => candidates ++ buildInternalCandidates()
    }

    findStrongerMatch(allCandidates.toList.sortBy(_.normalization).reverse, Nil).map { case (authoritativeURI, urlsToBeMigrated, urlsThatFailedContentCheck) => {
      urlsThatFailedContentCheck.foreach(failedContentCheckRepo.createOrIncrease(_, current.url))
      authoritativeURI.map(migrate(_, urlsToBeMigrated))
    }}
  }
}
