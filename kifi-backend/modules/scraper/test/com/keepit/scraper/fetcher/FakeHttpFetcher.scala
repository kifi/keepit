package com.keepit.scraper.fetcher

import com.keepit.common.akka.SafeFuture
import com.keepit.common.net.URI
import com.keepit.model.HttpProxy
import com.keepit.scraper.{ HttpRedirect, HttpInputStream }
import org.joda.time.DateTime
import play.api.http.Status

import scala.concurrent.Future

class FakeHttpFetcher(urlToResponse: Option[PartialFunction[String, HttpFetchStatus]] = None) extends HttpFetcher {
  def fetch(uri: URI, ifModifiedSince: Option[DateTime], proxy: Option[HttpProxy])(f: (HttpInputStream) => Unit): HttpFetchStatus = {
    val url = uri.toString()
    if (urlToResponse.exists(_.isDefinedAt(url))) {
      urlToResponse.get(url)
    } else HttpFetchStatus(Status.OK, None, new FetcherHttpContext {
      def destinationUrl: Option[String] = Some(url)
      def redirects: Seq[HttpRedirect] = Seq.empty
    })
  }

  implicit val fj = com.keepit.common.concurrent.ExecutionContext.fj
  def get(url: URI, ifModifiedSince: Option[DateTime], proxy: Option[HttpProxy])(f: (HttpInputStream) => Unit): Future[HttpFetchStatus] = SafeFuture {
    fetch(url, ifModifiedSince, proxy)(f)
  }
}
