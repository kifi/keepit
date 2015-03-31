package com.keepit.rover.article

import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.rover.article.content.DefaultContent
import com.keepit.rover.fetcher.{ FetchResult, RoverDocumentFetcher }
import com.keepit.common.time._
import com.keepit.rover.store.RoverArticleStore
import org.joda.time.DateTime

import scala.concurrent.{ Future, ExecutionContext }

class DefaultArticleFetcher @Inject() (
    articleStore: RoverArticleStore,
    documentFetcher: RoverDocumentFetcher,
    clock: Clock) extends ArticleFetcher[DefaultArticle] with Logging {

  def fetch(request: ArticleFetchRequest[DefaultArticle])(implicit ec: ExecutionContext): Future[Option[DefaultArticle]] = {
    val futureFetchedArticle = doFetch(request.url, request.lastFetchedAt)
    ArticleFetcher.resolveAndCompare(articleStore)(futureFetchedArticle, request.latestArticleKey, ArticleFetcher.defaultSimilarityCheck)
  }

  private def doFetch(url: String, ifModifiedSince: Option[DateTime])(implicit ec: ExecutionContext): Future[FetchResult[DefaultArticle]] = {
    documentFetcher.fetchTikaDocument(url, ifModifiedSince).map { result =>
      result.map { doc =>
        val content = DefaultContent(
          destinationUrl = result.context.request.destinationUrl,
          title = doc.getTitle,
          description = doc.getDescription,
          content = doc.getContent,
          keywords = doc.getMetaKeywords,
          authors = doc.getAuthor.toSeq,
          openGraphType = doc.getOpenGraphType,
          publishedAt = doc.getPublishedAt,
          http = result.context,
          normalization = doc.getNormalizationInfo
        )
        DefaultArticle(clock.now(), url, content)
      }
    }
  }
}
