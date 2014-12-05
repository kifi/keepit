package com.keepit.scraper.extractor

import com.keepit.common.net.URI
import com.keepit.scraper.mediatypes.MediaTypes
import com.keepit.scraper.{ BasicArticle, SignatureBuilder, Signature, HttpInputStream }

import scala.util.{ Failure, Success }
import org.apache.commons.lang3.StringEscapeUtils.escapeHtml4

trait Extractor {
  def process(input: HttpInputStream): Unit
  def getContent(): String
  def getMetadata(name: String): Option[String]
  def getKeywords(): Option[String]
  def getLinks(key: String): Set[String]
  def getCanonicalUrl(destinationUrl: String): Option[String] = {
    getLinks("canonical").headOption orElse getMetadata("og:url") match {
      case Some(url) =>
        URI.parse(url) match {
          case Success(parsed) =>
            parsed.query match {
              //it seems like the spec does not restricts question marks to appear in the query part http://tools.ietf.org/html/rfc3986#section-3.4
              //still, if they exist in a query part of canonical urls its usually a bad url so we'll skip it to be safe.
              case Some(query) if query.params.exists(p => p.name.contains("?") || p.value.exists(_.contains("?"))) => None
              case _ if escapedDestination(destinationUrl, url) => Some(destinationUrl)
              case _ => Some(url)
            }
          case Failure(_) =>
            None
        }
      case _ => None
    }
  }
  private def escapedDestination(destinationUrl: String, url: String): Boolean = {
    val escaped = escapeHtml4(destinationUrl)
    if (escaped == url) return true
    val doubleEscaped = escapeHtml4(escaped)
    doubleEscaped == url
  }

  // helper methods
  def getAlternateUrls(): Set[String] = getLinks("alternate")
  def getTitle(): String = getMetadata("title").getOrElse("")
  def getDescription(): Option[String] = getMetadata("description")
  def getSignature(): Signature = {
    new SignatureBuilder().add(Seq(
      getTitle(),
      getDescription().getOrElse(""),
      getKeywords().getOrElse(""),
      getContent()
    )).build
  }
  def getMediaTypeString(): Option[String] = MediaTypes(this).getMediaTypeString(this)
  def basicArticle(destinationUrl: String): BasicArticle =
    BasicArticle(
      title = getTitle,
      content = getContent,
      canonicalUrl = getCanonicalUrl(destinationUrl),
      description = getDescription,
      media = getMediaTypeString,
      httpContentType = getMetadata("Content-Type"),
      httpOriginalContentCharset = getMetadata("Content-Encoding"),
      destinationUrl = destinationUrl,
      signature = getSignature
    )
}

trait ExtractorFactory extends Function[URI, Extractor]

abstract class ExtractorProvider extends PartialFunction[URI, Extractor]
