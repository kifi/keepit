package com.keepit.rover.fetcher.apache

import com.keepit.rover.fetcher.{ DeprecatedFetcherHttpContext, HttpRedirect }
import org.apache.http.protocol.{ HttpContext => ApacheHttpContext }

private[apache] class DeprecatedFetcherHttpContextAdaptor(context: ApacheHttpContext) extends DeprecatedFetcherHttpContext {
  def destinationUrl: Option[String] = Option(context.getAttribute("scraper_destination_url").asInstanceOf[String])
  def redirects: Seq[HttpRedirect] = Option(context.getAttribute("redirects").asInstanceOf[Seq[HttpRedirect]]).getOrElse(Seq.empty[HttpRedirect])
}