package com.keepit.scraper.extractor

import com.keepit.common.net.URI
import com.keepit.rover.extractor.utils.DateTimeMetadataParser
import com.keepit.rover.fetcher.{ FetchResult, HttpInputStream }
import com.keepit.scraper.mediatypes.MediaTypes
import com.keepit.scraper.{ BasicArticle, SignatureBuilder, Signature }

import scala.util.{ Failure, Success }
import org.apache.commons.lang3.StringEscapeUtils.unescapeHtml4
import org.joda.time.DateTime

trait Extractor {
  def process(input: FetchResult[HttpInputStream]): Unit
  def getContent(): String
  def getMetadata(name: String): Option[String]
  def getKeywords(): Option[String]
  def getLinks(key: String): Set[String]
  def getCanonicalUrl(destinationUrl: String): Option[String] = {
    getLinks("canonical").headOption orElse getMetadata("og:url") flatMap { url =>
      URI.absoluteUrl(destinationUrl, url).flatMap { absoluteUrl =>
        URI.parse(absoluteUrl) match {
          case Success(parsed) =>
            // Question marks are allowed in query parameter names and values, but their presence
            // in a canonical URL usually indicates a bad url.
            if (parsed.query.exists(_.params.exists(p => p.name.contains('?') || p.value.exists(_.contains('?'))))) {
              None
              // A common site error is copying the page URL directly into a canoncial URL tag, escaped an extra time.
            } else if (absoluteUrl.length > destinationUrl.length && unescapeHtml4(absoluteUrl) == destinationUrl) {
              None
            } else {
              Some(absoluteUrl)
            }
          case Failure(_) =>
            None
        }
      }
    }
  }

  // helper methods
  def getAlternateUrls(): Set[String] = getLinks("alternate")
  def getTitle(): String = getMetadata("title").getOrElse("")
  def getDescription(): Option[String] = getMetadata("description")
  def getAuthor(): Option[String] = getMetadata("author")
  def getPublishedAt(): Option[DateTime] = {
    Stream("article:published_time", "article:published", "ptime", "pdate").map(getMetadata).flatten.headOption.flatMap(DateTimeMetadataParser.parse)
  }
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
      alternateUrls = getAlternateUrls().take(3),
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
