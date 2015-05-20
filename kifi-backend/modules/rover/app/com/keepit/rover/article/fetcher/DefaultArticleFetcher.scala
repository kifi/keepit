package com.keepit.rover.article.fetcher

import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.time.{ Clock, _ }
import com.keepit.rover.article.DefaultArticle
import com.keepit.rover.article.content.DefaultContent
import com.keepit.rover.document.RoverDocumentFetcher
import com.keepit.rover.fetcher.FetchResult
import com.keepit.rover.store.RoverArticleStore
import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }

class DefaultArticleFetcher @Inject() (
    articleStore: RoverArticleStore,
    documentFetcher: RoverDocumentFetcher,
    clock: Clock) extends ArticleFetcher[DefaultArticle] with Logging {

  def fetch(request: ArticleFetchRequest[DefaultArticle])(implicit ec: ExecutionContext): Future[Option[DefaultArticle]] = {
    ArticleFetcher.fetchAndCompare(request, articleStore)(doFetch)
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
