package com.keepit.normalizer

import scala.concurrent.Future
import com.keepit.scraper.{ScrapeSchedulerPlugin, Signature}
import com.keepit.model.{URL, Normalization}
import com.keepit.scraper.extractor.{ExtractorProviderTypes}
import com.keepit.common.logging.Logging
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.net.{URI, URIParser}
import scala.util.{Failure, Success, Try}

trait ContentCheck extends PartialFunction[NormalizationCandidate, Future[Boolean]] {
  def getFailedAttempts(): Set[(String, String)]
  def apply(candidate: NormalizationCandidate) = check(candidate)
  protected def check(candidate: NormalizationCandidate): Future[Boolean]
}

case class SignatureCheck(referenceUrl: String, referenceSignature: Option[Signature] = None, trustedDomain: Option[String] = None)(implicit scraperPlugin: ScrapeSchedulerPlugin) extends ContentCheck with Logging {

  def isDefinedAt(candidate: NormalizationCandidate) = trustedDomain.map(candidate.url.matches) getOrElse candidate.isTrusted

  private def signature(url: String): Future[Option[Signature]] = URI.parse(url).get.host match {
    case Some(h) if h.name.trim.nonEmpty => scraperPlugin.getSignature(url, None)
    case _ => throw new IllegalArgumentException(s"url $url has no host!")
  }

  private lazy val referenceContentSignatureFuture = referenceSignature match {
    case None => signature(referenceUrl)
    case someSignature => Future.successful(someSignature)
  }
  private var failedContentChecks = Set.empty[String]
  private var referenceUrlIsBroken = false

  protected def check(candidate: NormalizationCandidate): Future[Boolean] = {
    val alternateUrl = candidate.url
    if (referenceUrlIsBroken || failedContentChecks.contains(alternateUrl)) Future.successful(false)
    else for {
      currentContentSignatureOption <- referenceContentSignatureFuture
      candidateContentSignatureOption <- if (currentContentSignatureOption.isDefined) signature(alternateUrl) else Future.successful(None)
    } yield (currentContentSignatureOption, candidateContentSignatureOption) match {
        case (Some(currentContentSignature), Some(candidateContentSignature)) => {
          val similarity = currentContentSignature.similarTo(candidateContentSignature)
          val threshold = 0.99 // todo(Léo): move to config
          val doTheyMatch =  similarity > threshold
          log.info(s"[${if (doTheyMatch) "ACCEPT" else "REJECT"} at $threshold] Content similarity of ${referenceUrl} and ${alternateUrl}: $similarity")
          doTheyMatch
        }
        case (Some(_), None) => {
          log.error(s"Content signature of URL ${alternateUrl} could not be computed.")
          failedContentChecks += alternateUrl; false
        }
        case (None, _) => {
          log.error(s"Content signature of reference URL ${referenceUrl} could not be computed.")
          referenceUrlIsBroken = true; false
        }
      }
  }

  def getFailedAttempts() = failedContentChecks.map((referenceUrl, _))

}

case class LinkedInProfileCheck(privateProfileId: Long)(implicit scraperPlugin: ScrapeSchedulerPlugin) extends ContentCheck with Logging {

  def isDefinedAt(candidate: NormalizationCandidate) = candidate.normalization == Normalization.CANONICAL && LinkedInNormalizer.linkedInCanonicalPublicProfile.findFirstIn(candidate.url).isDefined
  protected def check(publicProfileCandidate: NormalizationCandidate) = {
    for { idArticleOption <- scraperPlugin.scrapeBasicArticle(publicProfileCandidate.url, Some(ExtractorProviderTypes.LINKEDIN_ID)) } yield {println(idArticleOption); idArticleOption match {
      case Some(idArticle) => idArticle.content == privateProfileId.toString
      case None => {
        log.error(s"Content check of LinkedIn public profile ${publicProfileCandidate.url} for id ${privateProfileId} failed.")
        false
      }
    }}
  }
  def getFailedAttempts() = Set.empty
}
