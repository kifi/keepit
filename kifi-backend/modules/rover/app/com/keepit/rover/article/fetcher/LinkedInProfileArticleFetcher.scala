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

import scala.concurrent.{ ExecutionContext, Future }

class LinkedInProfileArticleFetcher @Inject() (
    articleStore: RoverArticleStore,
    documentFetcher: RoverDocumentFetcher,
    clock: Clock) extends ArticleFetcher[LinkedInProfileArticle] with Logging {

  def fetch(request: ArticleFetchRequest[LinkedInProfileArticle])(implicit ec: ExecutionContext): Future[Option[LinkedInProfileArticle]] = {
    val futureFetchedArticle = doFetch(request.url, request.lastFetchedAt)
    ArticleFetcher.resolveAndCompare(articleStore)(futureFetchedArticle, request.latestArticleKey, ArticleFetcher.defaultSimilarityCheck)
  }

  def doFetch(url: String, ifModifiedSince: Option[DateTime])(implicit ec: ExecutionContext): Future[FetchResult[LinkedInProfileArticle]] = {
    documentFetcher.fetchJsoupDocument(url, ifModifiedSince).map { result =>
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

  private val idPattern = """newTrkInfo = '([0-9]{1,20}),' \+ document.referrer.substr\(0\,128\)""".r
  private def getLinkedInProfile(doc: JsoupDocument): LinkedInProfile = {
    val title = doc.doc.getElementById("member-1").text
    val overview = doc.doc.select("[id=overview] dd").text
    val sections = doc.doc.select("[id^=profile-] .content").text
    val id = idPattern.findFirstMatchIn(doc.doc.getElementsByTag("script").toString).map { case idPattern(id) => id }
    if (id.isEmpty) { log.error(s"Could not find LinkedIn id at ${doc.doc.location}") }
    LinkedInProfile(id, title, overview, sections)
  }
}

