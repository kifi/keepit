package com.keepit.normalizer

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.net.URI
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.model.{Normalization, NormalizedURIRepo, UriNormalizationRuleRepo, NormalizedURI}
import com.keepit.common.logging.Logging
import com.keepit.scraper.ScraperPlugin
import scala.concurrent.ExecutionContext.Implicits.global
import com.keepit.common.akka.MonitoredAwait
import scala.concurrent.duration._
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError, HealthcheckPlugin}
import scala.util.{Try, Success, Failure}

@ImplementedBy(classOf[NormalizationServiceImpl])
trait NormalizationService {
  def update(current: NormalizedURI, candidates: NormalizationCandidate*)(implicit normalizedURIRepo: NormalizedURIRepo, session: RWSession): Option[NormalizedURI]
  def normalize(uriString: String)(implicit session: RSession): String
}

@Singleton
class NormalizationServiceImpl @Inject() (normalizationRuleRepo: UriNormalizationRuleRepo, scraperPlugin: ScraperPlugin, monitoredAwait: MonitoredAwait, healthcheckPlugin: HealthcheckPlugin) extends NormalizationService with Logging {

  def normalize(uriString: String)(implicit session: RSession): String = {
    val prepUrlTry = for {
      uri <- URI.parse(uriString)
      prepUrl <- Try { PreNormalizer(uri).toString() }
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

  def update(current: NormalizedURI, candidates: NormalizationCandidate*)(implicit normalizedURIRepo: NormalizedURIRepo, session: RWSession): Option[NormalizedURI] = {

    // Inner methods
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

    def findStrongerMatch(orderedCandidates: List[NormalizationCandidate]): (Option[NormalizedURI], Seq[String]) = orderedCandidates match {
      case Nil => (None, Nil)
      case strongerCandidate::weakerCandidates => {
        assert(current.normalization.isEmpty || current.normalization.get <= strongerCandidate.normalization)
        assert(weakerCandidates.isEmpty || weakerCandidates.head.normalization <= strongerCandidate.normalization)

        val strongerCandidateUrl = normalize(strongerCandidate.url)

        if (current.url == strongerCandidateUrl) {
          if (current.normalization == strongerCandidate.normalization) (None, Nil)
          else {
            val currentWithUpgradedNormalization = current.withNormalization(strongerCandidate.normalization)
            val toBeMigrated = weakerCandidates.takeWhile(current.normalization.isEmpty || _.normalization >= current.normalization.get).map(_.url)
            (Some(currentWithUpgradedNormalization), toBeMigrated)
          }
        }

        else {
          val candidateNormalizedURI = normalizedURIRepo.getByUri(strongerCandidateUrl) match {
            case None => NormalizedURI.withHash(normalizedUrl = strongerCandidateUrl, normalization = Some(strongerCandidate.normalization))
            case Some(uri) =>
              if (uri.normalization.isEmpty || uri.normalization.get < strongerCandidate.normalization)
                uri.withNormalization(strongerCandidate.normalization)
              else uri
          }
          if (checkSignature(candidateNormalizedURI.url)) {
            val toBeMigrated = current.url :: weakerCandidates.takeWhile(current.normalization.isEmpty || _.normalization >= current.normalization.get).map(_.url)
            (Some(candidateNormalizedURI), toBeMigrated)
          }
          else findStrongerMatch(weakerCandidates)
        }
      }
    }

    lazy val currentContentSignatureFuture = scraperPlugin.asyncSignature(current.url)
    def checkSignature(normalizedCandidateUrl: String): Boolean = {
      val signaturesFuture = for {
        currentContentSignatureOption <- currentContentSignatureFuture
        candidateContentSignatureOption <- scraperPlugin.asyncSignature(normalizedCandidateUrl) if currentContentSignatureOption.isDefined
      } yield (currentContentSignatureOption, candidateContentSignatureOption)

      val signatures = monitoredAwait.result(signaturesFuture, 2 minutes , s"Content signatures of URLs ${current.url} and ${normalizedCandidateUrl} could not be compared.", (None, None))

      signatures match {
        case (Some(currentContentSignature), Some(candidateContentSignature)) => currentContentSignature.similarTo(candidateContentSignature) > 0.9
        case _ => false
      }
    }

    def migrate(authoritativeURI: NormalizedURI, urlsToBeMigrated: Seq[String]): NormalizedURI = {
      normalizedURIRepo.save(authoritativeURI)
      // For each existing url, add rule, grandfather references
    }

    //Main routine

    val allCandidates = current.normalization match {
      case Some(currentNormalization) => candidates.filter(_.normalization >= currentNormalization)
      case None => candidates ++ buildInternalCandidates()
    }

    val (authoritativeURI, urlsToBeMigrated) = findStrongerMatch(allCandidates.toList.sortBy(_.normalization).reverse)
    authoritativeURI.map(migrate(_, urlsToBeMigrated))

  }
}
