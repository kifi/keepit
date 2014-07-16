package com.keepit.scraper.fetcher.apache

import org.apache.http.protocol.{ HttpContext => ApacheHttpContext }
import com.keepit.scraper.{ HttpRedirect, FetcherHttpContext }

private[apache] class FetcherHttpContextAdaptor(context: ApacheHttpContext) extends FetcherHttpContext {
  def destinationUrl: Option[String] = Option(context.getAttribute("scraper_destination_url").asInstanceOf[String])
  def redirects: Seq[HttpRedirect] = Option(context.getAttribute("redirects").asInstanceOf[Seq[HttpRedirect]]).getOrElse(Seq.empty[HttpRedirect])
}