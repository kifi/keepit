package com.keepit.scraper

import org.joda.time.DateTime
import com.keepit.model.HttpProxy
import org.apache.http.protocol.HttpContext
import org.apache.http.HttpStatus
import com.keepit.common.net.URI

trait HttpFetcher {
  def fetch(url: String, ifModifiedSince: Option[DateTime] = None, proxy: Option[HttpProxy] = None)(f: HttpInputStream => Unit): HttpFetchStatus
  def close()
}

case class HttpFetchStatus(statusCode: Int, message: Option[String], context: HttpContext) {
  def destinationUrl = Option(context.getAttribute("scraper_destination_url").asInstanceOf[String])
  def redirects = Option(context.getAttribute("redirects").asInstanceOf[Seq[HttpRedirect]]).getOrElse(Seq.empty[HttpRedirect])
}

case class HttpRedirect(statusCode: Int, currentLocation: String, newDestination: String) {
  def isPermanent = (statusCode == HttpStatus.SC_MOVED_PERMANENTLY)
  def isAbsolute = URI.isAbsolute(currentLocation) && URI.isAbsolute(newDestination)
  def isLocatedAt(url: String) = (currentLocation == url)
}

object HttpRedirect {
  def withStandardizationEffort(statusCode: Int, currentLocation: String, newDestination: String): HttpRedirect = HttpRedirect(statusCode, currentLocation, URI.url(currentLocation, newDestination))

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  implicit val format = (
    (__ \ 'statusCode).format[Int] and
    (__ \ 'currentLocation).format[String] and
    (__ \ 'newDestination).format[String]
  )(HttpRedirect.apply _, unlift(HttpRedirect.unapply))
}
