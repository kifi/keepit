package com.keepit.scraper

import com.keepit.common.net.URI
import play.api.http.Status._

trait FetcherHttpContext {
  def destinationUrl: Option[String]
  def redirects: Seq[HttpRedirect]
}

case class HttpFetchStatus(statusCode: Int, message: Option[String], context: FetcherHttpContext) {
  def destinationUrl = context.destinationUrl
  def redirects = context.redirects
}

case class HttpRedirect(statusCode: Int, currentLocation: String, newDestination: String) {
  def isPermanent: Boolean = (statusCode == MOVED_PERMANENTLY)
  def isAbsolute: Boolean = URI.isAbsolute(currentLocation) && URI.isAbsolute(newDestination)
  def isLocatedAt(url: String): Boolean = (currentLocation == url)
}

object HttpRedirect {
  def withStandardizationEffort(statusCode: Int, currentLocation: String, destination: String): HttpRedirect = {
    def escaped = escapeLong25(destination)
    val newDestination = URI.absoluteUrl(currentLocation, escaped).getOrElse(escaped)
    HttpRedirect(statusCode, currentLocation, newDestination)
  }

  private val AmpersandLongEscaping = "%25(25)+".r
  private val AmpersandNormalizedEscaping = "%25%"

  private def escapeLong25(url: String): String = {
    AmpersandLongEscaping.replaceAllIn(url, AmpersandNormalizedEscaping)
  }

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
    redirects.takeWhile(_.isPermanent).foreach {
      case permanentRedirect =>
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
