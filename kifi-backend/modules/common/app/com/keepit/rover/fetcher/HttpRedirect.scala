package com.keepit.rover.fetcher

import com.keepit.common.net.URI
import com.keepit.scraper.ShortenedUrls
import play.api.http.Status._

case class HttpRedirect(statusCode: Int, currentLocation: String, newDestination: String) {
  def isPermanent: Boolean = (statusCode == MOVED_PERMANENTLY)
  def isAbsolute: Boolean = URI.isAbsolute(currentLocation) && URI.isAbsolute(newDestination)
  def isLocatedAt(url: String): Boolean = (currentLocation == url)
  def isShortener: Boolean = HttpRedirect.isShortenedUrl(currentLocation)
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

  def resolve(origin: String, redirects: Seq[HttpRedirect]): Option[String] = {
    var absoluteDestination = origin
    var currentLocation = origin
    val relevantRedirects = redirects.takeWhile(redirect => redirect.isPermanent || redirect.isShortener)
    relevantRedirects.foreach {
      case redirect =>
        if (redirect.isLocatedAt(currentLocation)) {
          currentLocation = redirect.newDestination
          if (URI.isAbsolute(currentLocation)) {
            absoluteDestination = currentLocation
          }
        }
    }
    if (origin != absoluteDestination) Some(absoluteDestination) else None
  }

  def isShortenedUrl(url: String) = URI.parse(url).toOption.flatMap(_.host.map(_.name)).exists(ShortenedUrls.domains.contains)
}
