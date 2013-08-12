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
  def update(current: NormalizedURI, candidates: NormalizationCandidate*)(implicit normalizedURIRepo: NormalizedURIRepo, session: RWSession): Option[NormalizedURI]
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

  def update(current: NormalizedURI, candidates: NormalizationCandidate*)(implicit normalizedURIRepo: NormalizedURIRepo, session: RWSession): Option[NormalizedURI] = {

    // Inner methods
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

    // Main code
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

    val (authoritativeURI, urlsToBeMigrated) = findStrongerMatch(allCandidates.toList.sortBy(_.normalization).reverse)
    authoritativeURI.map(migrate(_, urlsToBeMigrated))

  }
}
