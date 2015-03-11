package com.keepit.rover.fetcher.apache

import com.keepit.rover.fetcher.{ FetchContext, HttpRedirect }
import org.apache.http.HttpHeaders._
import org.apache.http.protocol.HttpContext
import org.apache.http.{ HttpResponseInterceptor, HttpResponse }

object RedirectInterceptor {
  val scraperDestinationUrlAttribute = "scraper_destination_url"
  val redirectsAttribute = "redirects"

  def setRedirectionAttributes(httpContext: HttpContext, destinationUrl: String, redirects: Seq[HttpRedirect]): Unit = {
    httpContext.setAttribute(scraperDestinationUrlAttribute, destinationUrl)
    httpContext.setAttribute(redirectsAttribute, Seq.empty[HttpRedirect])
  }

  def getRedirects(httpContext: HttpContext): Option[Seq[HttpRedirect]] = {
    Option(httpContext.getAttribute(redirectsAttribute).asInstanceOf[Seq[HttpRedirect]])
  }

  def getDestinationUrl(httpContext: HttpContext): Option[String] = {
    Option(httpContext.getAttribute(scraperDestinationUrlAttribute).asInstanceOf[String])
  }

  def getFetchContext(httpContext: HttpContext): Option[FetchContext] = {
    for {
      destinationUrl <- getDestinationUrl(httpContext)
      redirects <- getRedirects(httpContext)
    } yield FetchContext(destinationUrl, redirects)
  }
}

class RedirectInterceptor extends HttpResponseInterceptor {
  import RedirectInterceptor._
  def process(response: HttpResponse, context: HttpContext) {
    Option(response.getFirstHeader(LOCATION)).map(_.getValue).foreach { newDestination =>
      val currentLocation = getDestinationUrl(context).get
      val redirect = HttpRedirect.withStandardizationEffort(response.getStatusLine.getStatusCode, currentLocation, newDestination)
      val redirects = getRedirects(context).get :+ redirect
      setRedirectionAttributes(context, redirect.newDestination, redirects)
    }
  }
}
