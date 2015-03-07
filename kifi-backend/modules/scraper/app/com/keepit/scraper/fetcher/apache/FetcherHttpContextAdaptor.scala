package com.keepit.scraper.fetcher.apache

import com.keepit.rover.fetcher.HttpRedirect
import com.keepit.scraper.fetcher.FetcherHttpContext
import org.apache.http.protocol.{ HttpContext => ApacheHttpContext }

private[apache] class FetcherHttpContextAdaptor(context: ApacheHttpContext) extends FetcherHttpContext {
  def destinationUrl: Option[String] = Option(context.getAttribute("scraper_destination_url").asInstanceOf[String])
  def redirects: Seq[HttpRedirect] = Option(context.getAttribute("redirects").asInstanceOf[Seq[HttpRedirect]]).getOrElse(Seq.empty[HttpRedirect])
}