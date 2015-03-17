package com.keepit.rover.article

import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.rover.fetcher.{ FetchResult, RoverDocumentFetcher }
import com.keepit.common.time._
import org.joda.time.DateTime

import scala.concurrent.{ Future, ExecutionContext }

class DefaultArticleFetcher @Inject() (
    documentFetcher: RoverDocumentFetcher,
    clock: Clock,
    implicit val executionContext: ExecutionContext) extends Logging {

  def fetch(url: String, ifModifiedSince: Option[DateTime]): Future[FetchResult[DefaultArticle]] = {
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
