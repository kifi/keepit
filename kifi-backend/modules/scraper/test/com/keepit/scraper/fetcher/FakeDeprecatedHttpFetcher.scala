package com.keepit.scraper.fetcher

import com.keepit.rover.fetcher._
import play.api.http.Status

import scala.concurrent.Future

class FakeDeprecatedHttpFetcher(urlToResponse: Option[PartialFunction[String, DeprecatedHttpFetchStatus]] = None) extends DeprecatedHttpFetcher {
  def fetch(request: FetchRequest)(f: (FetchResult) => Unit): DeprecatedHttpFetchStatus = {
    val url = request.url
    if (urlToResponse.exists(_.isDefinedAt(url))) {
      urlToResponse.get(url)
    } else DeprecatedHttpFetchStatus(Status.OK, None, None)
  }

  implicit val fj = com.keepit.common.concurrent.ExecutionContext.fj
  def get(request: FetchRequest)(f: (FetchResult) => Unit): Future[DeprecatedHttpFetchStatus] = Future.successful {
    fetch(request)(f)
  }
}
