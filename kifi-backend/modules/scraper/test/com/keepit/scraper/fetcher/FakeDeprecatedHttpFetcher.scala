package com.keepit.scraper.fetcher

import com.keepit.common.akka.SafeFuture
import com.keepit.common.net.URI
import com.keepit.model.HttpProxy
import com.keepit.rover.fetcher._
import org.joda.time.DateTime
import play.api.http.Status

import scala.concurrent.Future

class FakeDeprecatedHttpFetcher(urlToResponse: Option[PartialFunction[String, DeprecatedHttpFetchStatus]] = None) extends DeprecatedHttpFetcher {
  def fetch(uri: URI, ifModifiedSince: Option[DateTime], proxy: Option[HttpProxy])(f: (HttpInputStream) => Unit): DeprecatedHttpFetchStatus = {
    val url = uri.toString()
    if (urlToResponse.exists(_.isDefinedAt(url))) {
      urlToResponse.get(url)
    } else DeprecatedHttpFetchStatus(Status.OK, None, new DeprecatedFetcherHttpContext {
      def destinationUrl: Option[String] = Some(url)
      def redirects: Seq[HttpRedirect] = Seq.empty
    })
  }

  implicit val fj = com.keepit.common.concurrent.ExecutionContext.fj
  def get(url: URI, ifModifiedSince: Option[DateTime], proxy: Option[HttpProxy])(f: (HttpInputStream) => Unit): Future[DeprecatedHttpFetchStatus] = SafeFuture {
    fetch(url, ifModifiedSince, proxy)(f)
  }
}
