package com.keepit.normalizer

import com.keepit.common.db.slick.DBSession.RSession
import scala.concurrent.Future
import com.keepit.scraper.{ScraperPlugin, Scraper, Signature}
import com.keepit.common.net.URI
import com.keepit.model.Normalization
import com.keepit.scraper.extractor.JsoupBasedExtractor
import org.jsoup.nodes.Document
import com.keepit.common.logging.Logging
import scala.concurrent.ExecutionContext.Implicits.global

trait ContentCheck extends PartialFunction[NormalizationCandidate, RSession => Future[Boolean]] {
  def getFailedAttempts(): Set[(String, String)]
  def apply(candidate: NormalizationCandidate) = { implicit session: RSession => check(candidate) }
  protected def check(candidate: NormalizationCandidate)(implicit session: RSession): Future[Boolean]
}

case class SignatureCheck(referenceUrl: String, trustedDomain: String)(implicit scraperPlugin: ScraperPlugin) extends ContentCheck with Logging {

  private def signature(url: String): Future[Option[Signature]] = scraperPlugin.scrapeBasicArticle(url).map { articleOption =>
    articleOption.map { article => Signature(Seq(article.title, article.description.getOrElse(""), article.content)) }
  }

  private lazy val referenceContentSignatureFuture = signature(referenceUrl)
  private var failedContentChecks = Set.empty[String]
  private var referenceUrlIsBroken = false

  def isDefinedAt(candidate: NormalizationCandidate) = ( for { uri <- URI.safelyParse(candidate.url); host <- uri.host } yield host.name.endsWith(trustedDomain) ).getOrElse(false)
  protected def check(candidate: NormalizationCandidate)(implicit session: RSession): Future[Boolean] = {
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

case class LinkedInProfileCheck(privateProfileId: Long)(implicit scraperPlugin: ScraperPlugin) extends ContentCheck {

  def isDefinedAt(candidate: NormalizationCandidate) = candidate.normalization == Normalization.CANONICAL && LinkedInNormalizer.linkedInPublicProfile.findFirstIn(candidate.url).isDefined
  protected def check(publicProfileCandidate: NormalizationCandidate)(implicit session: RSession) = {
    val idExtractor = new JsoupBasedExtractor(publicProfileCandidate.url, Scraper.maxContentChars) {
      def parse(doc: Document): String = doc.getElementsByTag("script").toString
    }

    for { publicProfileOption <- scraperPlugin.scrapeBasicArticle(publicProfileCandidate.url, Some(idExtractor)) } yield publicProfileOption match {
      case Some(article) => article.content.contains(s"newTrkInfo = '${privateProfileId},' + document.referrer.substr(0,128)")
      case None => false

    }
  }
  def getFailedAttempts() = Set.empty
}
