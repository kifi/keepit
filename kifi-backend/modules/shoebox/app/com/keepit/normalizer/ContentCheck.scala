package com.keepit.normalizer

import com.keepit.common.core._
import com.keepit.rover.article.Signature

import scala.concurrent.Future
import com.keepit.scraper.ScrapeScheduler
import com.keepit.model.Normalization
import com.keepit.scraper.extractor.{ ExtractorProviderTypes }
import com.keepit.common.logging.Logging
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.util.{ Failure, Success, Try }

trait ContentCheck extends PartialFunction[NormalizationCandidate, Future[Boolean]] {
  def getFailedAttempts(): Set[(String, String)]
  def apply(candidate: NormalizationCandidate) = check(candidate)
  protected def check(candidate: NormalizationCandidate): Future[Boolean]
}

case class SignatureCheck(referenceUrl: String, referenceSignature: Option[Signature] = None, trustedDomain: Option[String] = None)(implicit scraperPlugin: ScrapeScheduler) extends ContentCheck with Logging {

  Try { java.net.URI.create(referenceUrl) } match { // for debugging bad reference urls
    case Success(uri) => log.debug(s"[SignatureCheck] refUrl=$referenceUrl uri=$uri")
    case Failure(t) => throw new IllegalArgumentException(s"SignatureCheck -- failed to parse refUrl=$referenceUrl; Exception=$t; Cause=${t.getCause}", t)
  }

  def isDefinedAt(candidate: NormalizationCandidate) = {
    val isTrustedSource = trustedDomain.map(candidate.url.matches) getOrElse candidate.isTrusted
    lazy val isJavaUri = Try(java.net.URI.create(candidate.url)).isSuccess
    isTrustedSource && isJavaUri
  }

  private def signature(url: String): Future[Option[Signature]] = scraperPlugin.getSignature(url, None)

  private lazy val referenceContentSignatureFuture = referenceSignature match {
    case None => signature(referenceUrl)
    case someSignature => Future.successful(someSignature)
  }
  private var failedContentChecks = Set.empty[String]
  private var referenceUrlIsBroken = false

  protected def check(candidate: NormalizationCandidate): Future[Boolean] = {
    if (referenceUrlIsBroken || failedContentChecks.contains(candidate.url)) Future.successful(false)
    else for {
      currentContentSignatureOption <- referenceContentSignatureFuture
      candidateContentSignatureOption <- if (currentContentSignatureOption.isDefined) signature(candidate.url) else Future.successful(None)
    } yield (currentContentSignatureOption, candidateContentSignatureOption) match {
      case (Some(currentContentSignature), Some(candidateContentSignature)) => {
        val similarity = currentContentSignature.similarTo(candidateContentSignature)
        val threshold = 0.99 // todo(Léo): move to config
        val doTheyMatch = similarity >= threshold
        log.info(s"[${if (doTheyMatch) "ACCEPT" else "REJECT"} at $threshold] Content similarity of ${referenceUrl} and ${candidate.url}: $similarity")
        doTheyMatch
      }
      case (Some(_), None) => {
        log.error(s"Content signature of URL ${candidate.url} could not be computed.")
        failedContentChecks += candidate.url; false
      }
      case (None, _) => {
        log.error(s"Content signature of reference URL ${referenceUrl} could not be computed.")
        referenceUrlIsBroken = true; false
      }
    }
  }

  def getFailedAttempts() = failedContentChecks.map((referenceUrl, _))

}

case class LinkedInProfileCheck(privateProfileId: Long)(implicit scraperPlugin: ScrapeScheduler) extends ContentCheck with Logging {

  def isDefinedAt(candidate: NormalizationCandidate) = candidate.normalization == Normalization.CANONICAL && LinkedInNormalizer.linkedInCanonicalPublicProfile.findFirstIn(candidate.url).isDefined
  protected def check(publicProfileCandidate: NormalizationCandidate) = {
    for { idArticleOption <- scraperPlugin.scrapeBasicArticle(publicProfileCandidate.url, Some(ExtractorProviderTypes.LINKEDIN_ID)) } yield {
      idArticleOption match {
        case Some(idArticle) => idArticle.content == privateProfileId.toString
        case None => {
          log.error(s"Content check of LinkedIn public profile ${publicProfileCandidate.url} for id ${privateProfileId} failed.")
          false
        }
      }
    }
  }
  def getFailedAttempts() = Set.empty
}

case class AlternateUrlCheck(referenceUrl: String, prenormalize: String => Try[String])(implicit scraperPlugin: ScrapeScheduler) extends ContentCheck with Logging {

  private var failedContentChecks = Set.empty[String]

  private lazy val futureAlternateUrls: Future[Set[String]] = {
    scraperPlugin.scrapeBasicArticle(referenceUrl, None).map {
      case None => Set.empty[String]
      case Some(basicArticle) => basicArticle.alternateUrls.map(prenormalize(_).toOption).flatten
    }
  }

  def isDefinedAt(candidate: NormalizationCandidate) = candidate.isInstanceOf[AlternateCandidate]
  protected def check(alternateCandidate: NormalizationCandidate) = futureAlternateUrls.map { alternateUrls =>
    alternateUrls.contains(alternateCandidate.url) tap { isSuccessful => if (!isSuccessful) { failedContentChecks += alternateCandidate.url } }
  }

  def getFailedAttempts() = failedContentChecks.map((referenceUrl, _)).toSet
}