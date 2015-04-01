package com.keepit.rover.article

import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.rover.article.content.GithubContent
import com.keepit.rover.document.{RoverDocumentFetcher, JsoupDocument}
import com.keepit.rover.fetcher.FetchResult
import com.keepit.common.time._
import com.keepit.rover.store.{ RoverArticleStore, RoverUnderlyingArticleStore }
import org.joda.time.DateTime

import scala.concurrent.{ Future, ExecutionContext }

class GithubArticleFetcher @Inject() (
    articleStore: RoverArticleStore,
    documentFetcher: RoverDocumentFetcher,
    clock: Clock) extends ArticleFetcher[GithubArticle] with Logging {

  def fetch(request: ArticleFetchRequest[GithubArticle])(implicit ec: ExecutionContext): Future[Option[GithubArticle]] = {
    val futureFetchedArticle = doFetch(request.url, request.lastFetchedAt)
    ArticleFetcher.resolveAndCompare(articleStore)(futureFetchedArticle, request.latestArticleKey, ArticleFetcher.defaultSimilarityCheck)
  }

  private def doFetch(url: String, ifModifiedSince: Option[DateTime])(implicit ec: ExecutionContext): Future[FetchResult[GithubArticle]] = {
    documentFetcher.fetchJsoupDocument(url, ifModifiedSince).map { result =>
      result.map { doc =>
        val content = GithubContent(
          destinationUrl = result.context.request.destinationUrl,
          title = doc.getTitle,
          description = doc.getDescription,
          content = getContent(result.context.request.destinationUrl, doc),
          keywords = doc.getMetaKeywords,
          authors = doc.getAuthor.toSeq,
          openGraphType = doc.getOpenGraphType,
          publishedAt = doc.getPublishedAt,
          http = result.context,
          normalization = doc.getNormalizationInfo
        )
        GithubArticle(clock.now(), url, content)
      }
    }
  }

  private def getContent(destinationUrl: String, doc: JsoupDocument): Option[String] = {
    val content = getContentSelectors(destinationUrl, doc).map(doc.doc.select(_).text).mkString("\n")
    Some(content).filter(_.nonEmpty)
  }

  private def getContentSelectors(destinationUrl: String, doc: JsoupDocument): Set[String] = {
    // Determine which kind of page we're on
    if (destinationUrl.matches(".*/issues/.*")) {
      // Issue
      Set("h1.entry-title", ".discussion-topic-header", ".markdown-format", ".email-format")
    } else if (destinationUrl.matches(".*/issues")) {
      // Issues list
      Set("h1.entry-title", ".issues-list")
    } else if (destinationUrl.matches(".*/wiki.*")) {
      // Wiki
      Set("h1.entry-title", "#wiki-wrapper")
    } else if (destinationUrl.matches(".*/pull/.*")) {
      // Pull request
      Set("h1.entry-title", ".pull-description", ".discussion-topic-header", ".markdown-format", ".email-format")
    } else if (destinationUrl.matches(".*/pulls")) {
      // Pull requests
      Set("h1.entry-title", ".pulls-list")
    } else if (doc.doc.select("body.page-profile").first() != null) {
      // Profile
      Set(".profilecols .vcard", ".contributions-tab .popular-repos")
    } else if (doc.doc.select("body.page-blob").first() != null) {
      // Source page
      Set(".breadcrumb", ".frame-meta .commit", ".lines .highlight")
    } else if (doc.doc.select(".gist .data").first() != null) {
      // Gist
      Set(".meta .vcard", ".meta .info", ".data .line_data", "#comments")
    } else if (doc.doc.select(".repo-desc-homepage").first() != null) {
      // Repo main page
      Set("h1.entry-title", ".repo-desc-homepage", "#readme")
    } else {
      // Not sure. Be generous, grab common elements
      Set("h1.entry-title", ".markdown-format", ".email-format", "#readme", ".meta .info")
    }
  }
}

