package com.keepit.scraper.fetcher

import com.keepit.common.net.URI
import com.keepit.model.HttpProxy
import com.keepit.rover.fetcher.HttpRedirect
import com.keepit.scraper.HttpInputStream
import org.joda.time.DateTime

import scala.concurrent.Future

trait FetcherHttpContext {
  def destinationUrl: Option[String]
  def redirects: Seq[HttpRedirect]
}

case class HttpFetchStatus(statusCode: Int, message: Option[String], context: FetcherHttpContext) {
  def destinationUrl = context.destinationUrl
  def redirects = context.redirects
}

trait DeprecatedHttpFetcher {
  val NO_OP = { is: HttpInputStream => }
  // deprecated
  def fetch(url: URI, ifModifiedSince: Option[DateTime] = None, proxy: Option[HttpProxy] = None)(f: HttpInputStream => Unit): HttpFetchStatus
  def get(url: URI, ifModifiedSince: Option[DateTime] = None, proxy: Option[HttpProxy] = None)(f: HttpInputStream => Unit): Future[HttpFetchStatus]
}

