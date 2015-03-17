package com.keepit.rover.fetcher

import com.google.inject.{ Inject, Singleton }
import com.keepit.rover.extractor.tika.TikaDocument
import com.keepit.rover.extractor.JsoupDocument
import org.apache.http.HttpStatus
import org.apache.james.mime4j.dom.datetime.DateTime

import scala.concurrent.Future

@Singleton
class RoverDocumentFetcher @Inject() () {

  def fetch[A](url: String, ifModifiedSince: Option[DateTime] = None)(f: FetchResult[HttpInputStream] => A): Future[A] = {
    ???
  }

  def fetchJsoupDocument(url: String, ifModifiedSince: Option[DateTime] = None): Future[FetchResult[JsoupDocument]] = {
    fetch(url, ifModifiedSince) { result =>
      result.collect(HttpStatus.SC_OK) { input =>
        JsoupDocument.parse(input, result.context.request.destinationUrl).get
      }
    }
  }

  def fetchTikaDocument(url: String, ifModifiedSince: Option[DateTime] = None): Future[FetchResult[TikaDocument]] = {
    fetch(url, ifModifiedSince) { result =>
      result.collect(HttpStatus.SC_OK) { input =>
        TikaDocument.parse(input, result.context.request.destinationUrl, result.context.response.contentType)
      }
    }
  }
}
