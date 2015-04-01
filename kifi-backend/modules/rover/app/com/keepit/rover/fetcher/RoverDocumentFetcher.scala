package com.keepit.rover.fetcher

import com.google.inject.{ Inject, Singleton }
import com.keepit.rover.document.tika.TikaDocument
import com.keepit.rover.document.JsoupDocument
import org.apache.http.HttpStatus
import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class RoverDocumentFetcher @Inject() (httpFetcher: HttpFetcher) {

  def fetch[A](url: String, ifModifiedSince: Option[DateTime] = None)(f: FetchResult[HttpInputStream] => A)(implicit ec: ExecutionContext): Future[A] = {
    val proxy = None // todo(Léo): Implement proxy repo, proxy rules
    httpFetcher.fetch(FetchRequest(url, proxy, ifModifiedSince))(f)
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
