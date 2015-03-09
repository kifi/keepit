package com.keepit.rover.fetcher.apache

import com.keepit.rover.fetcher.HttpRedirect
import org.apache.http.HttpHeaders._
import org.apache.http.protocol.HttpContext
import org.apache.http.{ HttpResponseInterceptor, HttpResponse }

object RedirectInterceptor {
  private[apache] val scraperDestinationUrlAttribute = "scraper_destination_url"
  private[apache] val redirectsAttribute = "redirects"
}

class RedirectInterceptor extends HttpResponseInterceptor {
  import RedirectInterceptor._
  def process(response: HttpResponse, context: HttpContext) {
    if (response.containsHeader(LOCATION)) {
      val locations = response.getHeaders(LOCATION)
      if (locations.length > 0) {
        val currentLocation = context.getAttribute(scraperDestinationUrlAttribute).asInstanceOf[String]
        val redirect = HttpRedirect.withStandardizationEffort(response.getStatusLine.getStatusCode, currentLocation, locations(0).getValue())
        val redirects = context.getAttribute(redirectsAttribute).asInstanceOf[Seq[HttpRedirect]] :+ redirect
        context.setAttribute(redirectsAttribute, redirects)
        context.setAttribute(scraperDestinationUrlAttribute, redirect.newDestination)
      }
    }
  }
}
