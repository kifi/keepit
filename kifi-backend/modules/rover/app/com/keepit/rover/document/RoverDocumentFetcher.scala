package com.keepit.rover.document

import com.google.inject.{ Inject, Singleton }
import com.keepit.rover.document.tika.TikaDocument
import com.keepit.rover.fetcher.{ FetchRequest, FetchResult, HttpFetcher, HttpInputStream }
import org.apache.http.HttpStatus
import org.joda.time.DateTime
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class RoverDocumentFetcher @Inject() (httpFetcher: HttpFetcher, throttler: DomainFetchThrottler) {

  def fetch[A](url: String, ifModifiedSince: Option[DateTime] = None)(f: FetchResult[HttpInputStream] => A)(implicit ec: ExecutionContext): Future[A] = {
    val proxy = None // todo(Léo): Implement proxy repo, proxy rules
    def throttled(fetchResult: FetchResult[HttpInputStream]): A = {
      val destinationUrl = fetchResult.context.request.destinationUrl
      if (throttler.throttle(destinationUrl)) throw FetchThrottlingException(url, destinationUrl)
      else f(fetchResult)
    }
    httpFetcher.fetch(FetchRequest(url, proxy, ifModifiedSince))(throttled)
  }

  def fetchJsoupDocument(url: String, ifModifiedSince: Option[DateTime] = None)(implicit ec: ExecutionContext): Future[FetchResult[JsoupDocument]] = {
    fetch(url, ifModifiedSince) { result =>
      result.collect(HttpStatus.SC_OK) { input =>
        JsoupDocument.parse(input, result.context.request.destinationUrl).get
      }
    }
  }

  def fetchTikaDocument(url: String, ifModifiedSince: Option[DateTime] = None)(implicit ec: ExecutionContext): Future[FetchResult[TikaDocument]] = {
    fetch(url, ifModifiedSince) { result =>
      result.collect(HttpStatus.SC_OK) { input =>
        TikaDocument.parse(input, result.context.request.destinationUrl, result.context.response.contentType)
      }
    }
  }
}
