package com.keepit.rover.fetcher.apache

import com.keepit.rover.fetcher.{ FetchRequestInfo, HttpRedirect }
import org.apache.http.HttpHeaders._
import org.apache.http.protocol.HttpContext
import org.apache.http.{ HttpResponseInterceptor, HttpResponse }

object RedirectInterceptor {
  val destinationUrlAttribute = "destination_url"
  val redirectsAttribute = "redirects"

  def setRedirectionAttributes(httpContext: HttpContext, destinationUrl: String, redirects: Seq[HttpRedirect]): Unit = {
    httpContext.setAttribute(destinationUrlAttribute, destinationUrl)
    httpContext.setAttribute(redirectsAttribute, redirects)
  }

  def getRedirects(httpContext: HttpContext): Option[Seq[HttpRedirect]] = {
    Option(httpContext.getAttribute(redirectsAttribute).asInstanceOf[Seq[HttpRedirect]])
  }

  def getDestinationUrl(httpContext: HttpContext): Option[String] = {
    Option(httpContext.getAttribute(destinationUrlAttribute).asInstanceOf[String])
  }

  def getFetchRequestContext(httpContext: HttpContext): Option[FetchRequestInfo] = {
    for {
      destinationUrl <- getDestinationUrl(httpContext)
      redirects <- getRedirects(httpContext)
    } yield FetchRequestInfo(destinationUrl, redirects)
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
