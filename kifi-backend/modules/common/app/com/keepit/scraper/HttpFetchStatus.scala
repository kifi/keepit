package com.keepit.scraper

import org.apache.http.protocol.HttpContext
import org.apache.http.HttpStatus
import com.keepit.common.net.URI

case class HttpFetchStatus(statusCode: Int, message: Option[String], context: HttpContext) {
  def destinationUrl = Option(context.getAttribute("scraper_destination_url").asInstanceOf[String])
  def redirects = Option(context.getAttribute("redirects").asInstanceOf[Seq[HttpRedirect]]).getOrElse(Seq.empty[HttpRedirect])
}

case class HttpRedirect(statusCode: Int, currentLocation: String, newDestination: String) {
  def isPermanent: Boolean = (statusCode == HttpStatus.SC_MOVED_PERMANENTLY)
  def isAbsolute: Boolean = URI.isAbsolute(currentLocation) && URI.isAbsolute(newDestination)
  def isLocatedAt(url: String): Boolean = (currentLocation == url)
}

object HttpRedirect {
  def withStandardizationEffort(statusCode: Int, currentLocation: String, newDestination: String): HttpRedirect = HttpRedirect(statusCode, currentLocation, URI.absoluteUrl(currentLocation, newDestination).getOrElse(newDestination))

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  implicit val format = (
    (__ \ 'statusCode).format[Int] and
      (__ \ 'currentLocation).format[String] and
      (__ \ 'newDestination).format[String]
    )(HttpRedirect.apply _, unlift(HttpRedirect.unapply))

  def resolvePermanentRedirects(origin: String, redirects: Seq[HttpRedirect]): Option[String] = {
    var absoluteDestination = origin
    var currentLocation = origin
    redirects.takeWhile(_.isPermanent).foreach { case permanentRedirect =>
      if (permanentRedirect.isLocatedAt(currentLocation)) {
        currentLocation = permanentRedirect.newDestination
        if (URI.isAbsolute(currentLocation)) {
          absoluteDestination = currentLocation
        }
      }
    }
    if (origin != absoluteDestination) Some(absoluteDestination) else None
  }
}
