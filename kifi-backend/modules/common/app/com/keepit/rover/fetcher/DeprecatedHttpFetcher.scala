package com.keepit.rover.fetcher

import com.keepit.common.net.URI
import com.keepit.model.HttpProxy
import org.joda.time.DateTime

import scala.concurrent.Future

trait DeprecatedFetcherHttpContext {
  def destinationUrl: Option[String]
  def redirects: Seq[HttpRedirect]
}

case class DeprecatedHttpFetchStatus(statusCode: Int, message: Option[String], context: DeprecatedFetcherHttpContext) {
  def destinationUrl = context.destinationUrl
  def redirects = context.redirects
}

trait DeprecatedHttpFetcher {
  val NO_OP = { is: HttpInputStream => }
  // deprecated
  def fetch(url: URI, ifModifiedSince: Option[DateTime] = None, proxy: Option[HttpProxy] = None)(f: HttpInputStream => Unit): DeprecatedHttpFetchStatus
  def get(url: URI, ifModifiedSince: Option[DateTime] = None, proxy: Option[HttpProxy] = None)(f: HttpInputStream => Unit): Future[DeprecatedHttpFetchStatus]
}

case class ScraperHttpConfig(
  httpFetcherEnforcerFreq: Int,
  httpFetcherQSizeThreshold: Int)

