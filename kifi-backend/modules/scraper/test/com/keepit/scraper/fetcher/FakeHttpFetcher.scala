package com.keepit.scraper.fetcher

import com.keepit.model.HttpProxy
import com.keepit.scraper.{ HttpRedirect, FetcherHttpContext, HttpFetchStatus, HttpInputStream }
import org.joda.time.DateTime
import play.api.http.Status

class FakeHttpFetcher(urlToResponse: Option[PartialFunction[String, HttpFetchStatus]] = None) extends HttpFetcher {
  override def fetch(url: String, ifModifiedSince: Option[DateTime], proxy: Option[HttpProxy])(f: (HttpInputStream) => Unit): HttpFetchStatus = {
    if (urlToResponse.exists(_.isDefinedAt(url))) {
      urlToResponse.get(url)
    } else HttpFetchStatus(Status.OK, None, new FetcherHttpContext {
      def destinationUrl: Option[String] = Some(url)
      def redirects: Seq[HttpRedirect] = Seq.empty
    })
  }
}
