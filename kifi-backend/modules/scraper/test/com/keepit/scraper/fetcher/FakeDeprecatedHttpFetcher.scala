package com.keepit.scraper.fetcher

import com.keepit.rover.fetcher._
import play.api.http.Status

import scala.concurrent.Future

class FakeDeprecatedHttpFetcher(urlToResponse: Option[PartialFunction[String, DeprecatedHttpFetchStatus]] = None) extends DeprecatedHttpFetcher {
  def fetch(request: FetchRequest)(f: (HttpInputStream) => Unit): DeprecatedHttpFetchStatus = {
    val url = request.url
    if (urlToResponse.exists(_.isDefinedAt(url))) {
      urlToResponse.get(url)
    } else DeprecatedHttpFetchStatus(Status.OK, None, new DeprecatedFetcherHttpContext {
      def destinationUrl: Option[String] = Some(url)
      def redirects: Seq[HttpRedirect] = Seq.empty
    })
  }

  implicit val fj = com.keepit.common.concurrent.ExecutionContext.fj
  def get(request: FetchRequest)(f: (HttpInputStream) => Unit): Future[DeprecatedHttpFetchStatus] = Future.successful {
    fetch(request)(f)
  }
}
