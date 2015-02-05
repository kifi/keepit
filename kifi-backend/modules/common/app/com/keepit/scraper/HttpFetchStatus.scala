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
    redirects.foreach {
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

  private val shortenedUrls = Set("bit.ly", "goo.gl", "owl.ly", "deck.ly", "su.pr", "lnk.co", "fur.ly", "ow.ly", "owl.ly", "tinyurl.com", "is.gd", "v.gd", "t.co", "linkd.in", "urls.im", "tnw.to", "instagr.am", "spr.ly", "nyp.st", "rww.to", "itun.es", "youtu.be", "spoti.fi", "j.mp", "amzn.to", "lnkd.in", "trib.al", "fb.me", "buff.ly", "qr.ae", "tcrn.ch", "nzzl.me", "kiss.ly", "wp.me", "nyti.ms", "pocket.co", "onforb.es")
  def isShortenedUrl(url: String) = URI.parse(url).toOption.flatMap(_.host.map(_.name)).exists(shortenedUrls.contains)
}
