package com.keepit.normalizer

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.net.URI
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.model.{Normalization, NormalizedURIRepo, UriNormalizationRuleRepo, NormalizedURI}
import com.keepit.common.logging.Logging
import scala.util.{Success, Failure}
import com.keepit.scraper.ScraperPlugin
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import com.keepit.common.akka.MonitoredAwait
import scala.concurrent.duration._

trait URINormalizer extends PartialFunction[URI, URI]

@ImplementedBy(classOf[NormalizationServiceImpl])
trait NormalizationService {
  def update(current: NormalizedURI, candidates: NormalizationCandidate*)(implicit normalizedURIRepo: NormalizedURIRepo, session: RWSession): Unit
  def normalize(uriString: String)(implicit session: RSession): String
}

@Singleton
class NormalizationServiceImpl @Inject() (normalizationRuleRepo: UriNormalizationRuleRepo, scraperPlugin: ScraperPlugin, monitoredAwait: MonitoredAwait) extends NormalizationService with Logging {
  val normalizers = Seq(AmazonNormalizer, GoogleNormalizer, YoutubeNormalizer, RemoveWWWNormalizer, LinkedInNormalizer, DefaultNormalizer)

  def normalize(uriString: String)(implicit session: RSession): String = {
    val prepUrl = for {
      uri <- URI.safelyParse(uriString)
      prepUri <- normalizers.find(_.isDefinedAt(uri)).map(_.apply(uri))
      prepUrl <- prepUri.safelyToString()
    } yield prepUrl

    val mappedUrl = for {
      prepUrl <- prepUrl
      mappedUrl <- normalizationRuleRepo.getByUrl(prepUrl)
    } yield mappedUrl

    mappedUrl.getOrElse(prepUrl.getOrElse(uriString))
  }

  def update(current: NormalizedURI, candidates: NormalizationCandidate*)(implicit normalizedURIRepo: NormalizedURIRepo, session: RWSession): Unit = {

    lazy val internalCandidates = URI.safelyParse(current.url) match {
      case None => Seq.empty[NormalizationCandidate]
      case Some(currentURI) => for {
        normalization <- Normalization.priority.keys if normalization <= Normalization.HTTPS
        candidateUrl <- currentURI.withScheme(normalization.scheme).safelyToString()
      } yield NormalizationCandidate(candidateUrl, normalization)
    }

    val allCandidates = current.normalization match {
     case Some(currentNormalization) => candidates.filter(_.normalization >= currentNormalization)
     case None => candidates ++ internalCandidates
    }

    lazy val currentContentSignatureFuture = scraperPlugin.asyncSignature(current.url)
    process(allCandidates.toList.sortBy(_.normalization).reverse)

    def process(orderedCandidates: List[NormalizationCandidate]): Unit = orderedCandidates match {
      case Nil =>
      case strongerCandidate::weakerCandidates => {
        val strongerCandidateUrl = normalize(strongerCandidate.url)

        if (current.url == strongerCandidateUrl && (current.normalization.isEmpty || current.normalization.get < strongerCandidate.normalization)) {
          val currentUpdated = current.copy(normalization = Some(strongerCandidate.normalization))
          normalizedURIRepo.save(currentUpdated)
          val tobeGrandFathered = weakerCandidates.takeWhile(current.normalization.isEmpty || _.normalization >= current.normalization.get).map(_.url)
          if (current.normalization.isEmpty) grandfather(currentUpdated, tobeGrandFathered)
        }

        else {
          val candidateNormalizedURI = normalizedURIRepo.getByUri(strongerCandidateUrl)
          candidateNormalizedURI match {
            case None => process(weakerCandidates)
            case Some(uri) => {
              val uriUpdated =
                if (uri.normalization.isEmpty || uri.normalization.get < strongerCandidate.normalization)
                  normalizedURIRepo.save(uri.copy(normalization = Some(strongerCandidate.normalization)))
                else uri

              if (checkSignature(uriUpdated.url)) {
                val tobeGrandFathered = Seq(current.url) ++ weakerCandidates.takeWhile(uri.normalization.isEmpty || _.normalization >= uri.normalization.get).map(_.url)
                grandfather(uriUpdated, tobeGrandFathered)
              }
            }
          }
        }
      }
    }

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

    def grandfather(authoritativeURI: NormalizedURI, oldUrls: Seq[String]): Unit = {
    }

  }
}
