package com.keepit.normalizer

import scala.concurrent.Future
import com.keepit.scraper.{ScraperPlugin, ScraperConfig, Signature}
import com.keepit.model.Normalization
import com.keepit.scraper.extractor.LinkedInIdExtractor
import com.keepit.common.logging.Logging
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait ContentCheck extends PartialFunction[NormalizationCandidate, Future[Boolean]] {
  def getFailedAttempts(): Set[(String, String)]
  def apply(candidate: NormalizationCandidate) = check(candidate)
  protected def check(candidate: NormalizationCandidate): Future[Boolean]
}

case class SignatureCheck(referenceUrl: String, trustedDomain: String)(implicit scraperPlugin: ScraperPlugin) extends ContentCheck with Logging {

  private def signature(url: String): Future[Option[Signature]] = scraperPlugin.scrapeBasicArticle(url).map { articleOption =>
    articleOption.map { article => Signature(Seq(article.title, article.description.getOrElse(""), article.content)) }
  }

  private lazy val referenceContentSignatureFuture = signature(referenceUrl)
  private var failedContentChecks = Set.empty[String]
  private var referenceUrlIsBroken = false

  def isDefinedAt(candidate: NormalizationCandidate) = candidate.url.matches(trustedDomain)
  protected def check(candidate: NormalizationCandidate): Future[Boolean] = {
    val alternateUrl = candidate.url
    if (referenceUrlIsBroken || failedContentChecks.contains(alternateUrl)) Future.successful(false)
    else for {
      currentContentSignatureOption <- referenceContentSignatureFuture
      candidateContentSignatureOption <- if (currentContentSignatureOption.isDefined) signature(alternateUrl) else Future.successful(None)
    } yield (currentContentSignatureOption, candidateContentSignatureOption) match {
        case (Some(currentContentSignature), Some(candidateContentSignature)) => currentContentSignature.similarTo(candidateContentSignature) > 0.9
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

case class LinkedInProfileCheck(privateProfileId: Long)(implicit scraperPlugin: ScraperPlugin) extends ContentCheck with Logging {

  def isDefinedAt(candidate: NormalizationCandidate) = candidate.normalization == Normalization.CANONICAL && LinkedInNormalizer.linkedInCanonicalPublicProfile.findFirstIn(candidate.url).isDefined
  protected def check(publicProfileCandidate: NormalizationCandidate) = {
    val idExtractor = new LinkedInIdExtractor(publicProfileCandidate.url, ScraperConfig.maxContentChars)

    for { idArticleOption <- scraperPlugin.scrapeBasicArticle(publicProfileCandidate.url, Some(idExtractor)) } yield {println(idArticleOption); idArticleOption match {
      case Some(idArticle) => idArticle.content == privateProfileId.toString
      case None => {
        log.error(s"Content check of LinkedIn public profile ${publicProfileCandidate.url} for id ${privateProfileId} failed.")
        false
      }
    }}
  }
  def getFailedAttempts() = Set.empty
}
