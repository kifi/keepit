package com.keepit.scraper.fetcher

import com.keepit.model.HttpProxy
import com.keepit.scraper.{ HttpFetchStatus, HttpInputStream }
import org.joda.time.DateTime

import scala.concurrent.Future

trait HttpFetcher {
  val NO_OP = { is: HttpInputStream => }
  // deprecated
  def fetch(url: String, ifModifiedSince: Option[DateTime] = None, proxy: Option[HttpProxy] = None)(f: HttpInputStream => Unit): HttpFetchStatus
  def get(url: String, ifModifiedSince: Option[DateTime] = None, proxy: Option[HttpProxy] = None)(f: HttpInputStream => Unit): Future[HttpFetchStatus]
}

