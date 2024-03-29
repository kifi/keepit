package com.keepit.rover.document

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.rover.document.tika.{ MainContentHandler, TikaDocument }
import com.keepit.rover.fetcher.{ FetchRequest, FetchResult, HttpFetcher, HttpInputStream }
import com.keepit.rover.model.{ RoverHttpProxyRepo, RoverHttpProxy, RoverUrlRuleRepo }
import com.keepit.rover.rule.{ RoverUrlRuleCommander, RoverHttpProxyCommander, UrlRuleAction }
import com.keepit.rover.rule.UrlRuleAction.UseProxy
import org.apache.http.HttpStatus
import org.joda.time.DateTime
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class RoverDocumentFetcher @Inject() (
    httpFetcher: HttpFetcher,
    throttler: DomainFetchThrottler,
    urlRuleCommander: RoverUrlRuleCommander) {

  def fetch[A](url: String, ifModifiedSince: Option[DateTime] = None, shouldThrottle: Boolean)(f: FetchResult[HttpInputStream] => A)(implicit ec: ExecutionContext): Future[A] = {
    val proxy = urlRuleCommander.lightweightProxyFor(url)
    def throttled(fetchResult: FetchResult[HttpInputStream]): A = {
      val destinationUrl = fetchResult.context.request.destinationUrl
      if (shouldThrottle && throttler.throttle(destinationUrl)) throw FetchThrottlingException(url, destinationUrl)
      else f(fetchResult)
    }
    httpFetcher.fetch(FetchRequest(url, proxy, ifModifiedSince))(throttled)
  }

  def fetchJsoupDocument(url: String, ifModifiedSince: Option[DateTime] = None, shouldThrottle: Boolean)(implicit ec: ExecutionContext): Future[FetchResult[JsoupDocument]] = {
    fetch(url, ifModifiedSince, shouldThrottle) { result =>
      result.collect(HttpStatus.SC_OK) { input =>
        JsoupDocument.parse(input, result.context.request.destinationUrl).get
      }
    }
  }

  def fetchTikaDocument(url: String, ifModifiedSince: Option[DateTime] = None, shouldThrottle: Boolean, maxContentChars: Int = MainContentHandler.defaultMaxContentChars)(implicit ec: ExecutionContext): Future[FetchResult[TikaDocument]] = {
    fetch(url, ifModifiedSince, shouldThrottle) { result =>
      result.collect(HttpStatus.SC_OK) { input =>
        TikaDocument.parse(input, result.context.request.destinationUrl, result.context.response.contentType, maxContentChars)
      }
    }
  }
}
