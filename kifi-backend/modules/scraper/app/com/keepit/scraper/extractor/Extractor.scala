package com.keepit.scraper.extractor

import com.keepit.common.net.URI
import com.keepit.scraper.mediatypes.MediaTypes
import com.keepit.scraper.{ BasicArticle, SignatureBuilder, Signature, HttpInputStream }

trait Extractor {
  def process(input: HttpInputStream): Unit
  def getContent(): String
  def getMetadata(name: String): Option[String]
  def getKeywords(): Option[String]
  def getLinks(key: String): Set[String]
  def getCanonicalUrl(): Option[String] = {
    getLinks("canonical").headOption orElse getMetadata("og:url") match {
      case Some(url) if (URI.parse(url).isSuccess) => Some(url)
      case _ => None
    }
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
      canonicalUrl = getCanonicalUrl,
      description = getDescription,
      media = getMediaTypeString,
      httpContentType = getMetadata("Content-Type"),
      httpOriginalContentCharset = getMetadata("Content-Encoding"),
      destinationUrl = destinationUrl,
      signature = getSignature
    )
}

trait ExtractorFactory extends Function[String, Extractor]

abstract class ExtractorProvider extends PartialFunction[URI, Extractor]
