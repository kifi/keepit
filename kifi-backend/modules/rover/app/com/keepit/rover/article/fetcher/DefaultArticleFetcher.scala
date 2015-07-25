package com.keepit.rover.article.fetcher

import com.google.inject.Inject
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.logging.Logging
import com.keepit.common.time.{ Clock, _ }
import com.keepit.rover.article.DefaultArticle
import com.keepit.rover.article.content.DefaultContent
import com.keepit.rover.document.RoverDocumentFetcher
import com.keepit.rover.document.tika.TikaDocument
import com.keepit.rover.fetcher.{ FetchContext, HttpRedirect, FetchResult }
import com.keepit.rover.store.RoverArticleStore
import org.apache.http.HttpStatus
import org.joda.time.DateTime
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }

class DefaultArticleFetcher @Inject() (
    articleStore: RoverArticleStore,
    documentFetcher: RoverDocumentFetcher,
    clock: Clock) extends ArticleFetcher[DefaultArticle] with Logging {

  def fetch(request: ArticleFetchRequest[DefaultArticle])(implicit ec: ExecutionContext): Future[Option[DefaultArticle]] = {
    ArticleFetcher.fetchAndCompare(request, articleStore)(doFetch)
  }

  private val maxMetaRefreshRedirects = 10

  private def doFetch(url: String, ifModifiedSince: Option[DateTime], shouldThrottle: Boolean)(implicit ec: ExecutionContext): Future[FetchResult[DefaultArticle]] = {

    val futureResultWithPreviousRedirects = documentFetcher.fetchTikaDocument(url, ifModifiedSince, shouldThrottle).flatMap { initialResult =>
      FutureHelpers.foldLeftUntil[Int, (Seq[HttpRedirect], FetchResult[TikaDocument])](0 until maxMetaRefreshRedirects)((Seq.empty, initialResult)) {
        case ((previousRedirects, currentResult), _) =>
          val currentLocation = currentResult.context.request.destinationUrl

          currentResult.content.flatMap(_.getMetaRefresh) match {
            case Some((delay, newDestination)) if delay == 0 && newDestination != currentLocation => { // ignore delayed refreshes and circular redirects
              val refreshRedirect = HttpRedirect(HttpStatus.SC_MOVED_PERMANENTLY, currentLocation, newDestination)
              val updatedRedirects = previousRedirects ++ currentResult.context.request.redirects :+ refreshRedirect
              documentFetcher.fetchTikaDocument(newDestination, ifModifiedSince, false).imap { newResult =>
                ((updatedRedirects, newResult), false)
              }
            }

            case _ => Future.successful(((previousRedirects, currentResult), true))
          }
      }
    }

    futureResultWithPreviousRedirects.map {
      case (previousRedirects, result) =>
        result.map { doc =>
          val httpInfoWithPreviousRedirects = {
            val lastHttpInfo = FetchContext.toHttpInfo(result.context)
            lastHttpInfo.copy(redirects = previousRedirects ++ lastHttpInfo.redirects)
          }

          val content = DefaultContent(
            destinationUrl = result.context.request.destinationUrl,
            title = doc.getTitle,
            description = doc.getDescription,
            content = doc.getContent,
            keywords = doc.getMetaKeywords,
            authors = doc.getAuthor.toSeq,
            openGraphType = doc.getOpenGraphType,
            publishedAt = doc.getPublishedAt,
            http = httpInfoWithPreviousRedirects,
            normalization = doc.getNormalizationInfo
          )
          DefaultArticle(clock.now(), url, content)
        }
    }
  }
}
