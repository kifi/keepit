package com.keepit.rover.article.fetcher

import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.rover.article.LinkedInProfileArticle
import com.keepit.rover.article.content.{ LinkedInProfile, LinkedInProfileContent }
import com.keepit.rover.document.{ JsoupDocument, RoverDocumentFetcher }
import com.keepit.rover.fetcher.FetchResult
import com.keepit.rover.store.RoverArticleStore
import org.joda.time.DateTime
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }

class LinkedInProfileArticleFetcher @Inject() (
    articleStore: RoverArticleStore,
    documentFetcher: RoverDocumentFetcher,
    clock: Clock) extends ArticleFetcher[LinkedInProfileArticle] with Logging {

  def fetch(request: ArticleFetchRequest[LinkedInProfileArticle])(implicit ec: ExecutionContext): Future[Option[LinkedInProfileArticle]] = {
    ArticleFetcher.fetchAndCompare(request, articleStore)(doFetch)
  }

  def doFetch(url: String, ifModifiedSince: Option[DateTime], shouldThrottle: Boolean)(implicit ec: ExecutionContext): Future[FetchResult[LinkedInProfileArticle]] = {
    documentFetcher.fetchJsoupDocument(url, ifModifiedSince, shouldThrottle).map { result =>
      result.map { doc =>
        val content = LinkedInProfileContent(
          destinationUrl = result.context.request.destinationUrl,
          title = doc.getTitle,
          description = doc.getDescription,
          keywords = doc.getMetaKeywords,
          authors = doc.getAuthor.toSeq,
          openGraphType = doc.getOpenGraphType,
          publishedAt = doc.getPublishedAt,
          profile = getLinkedInProfile(doc),
          http = result.context,
          normalization = doc.getNormalizationInfo
        )
        LinkedInProfileArticle(clock.now(), url, content)
      }
    }
  }

  private val idPatterns = List(
    """endorsementData:\{recipientId:'([0-9]{1,20})'""".r,
    """newTrkInfo='([0-9]{1,20}),'\+document.referrer.substr\(0\,128\)""".r,
    """<div id="member-([0-9]{1,20})" class="masthead"""".r
  )

  private def getLinkedInProfile(doc: JsoupDocument): LinkedInProfile = {
    val docString = doc.doc.toString
    val title = s"${doc.doc.select(".full-name").text} - ${doc.doc.select(".title").text}"
    val overview = doc.doc.select(".profile-overview").text
    val sections = doc.doc.select("[class^=profile-]").text
    val id = idPatterns.flatMap(_.findFirstMatchIn(docString)).map(_.group(1)).countAll.maxByOpt(_._2).map(_._1)
    if (id.isEmpty) { log.error(s"Could not find LinkedIn id at ${doc.doc.location}") }
    LinkedInProfile(id, title, overview, sections)
  }

}

