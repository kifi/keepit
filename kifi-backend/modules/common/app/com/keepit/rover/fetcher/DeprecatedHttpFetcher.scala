package com.keepit.rover.fetcher

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
  def fetch(request: FetchRequest)(f: HttpInputStream => Unit): DeprecatedHttpFetchStatus
  def get(request: FetchRequest)(f: HttpInputStream => Unit): Future[DeprecatedHttpFetchStatus]
}

case class ScraperHttpConfig(
  httpFetcherEnforcerFreq: Int,
  httpFetcherQSizeThreshold: Int)

