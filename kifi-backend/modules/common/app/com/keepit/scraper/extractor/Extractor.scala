package com.keepit.scraper.extractor

import com.keepit.scraper.HttpInputStream
import com.keepit.common.net.URI

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
  def getAlternateUrls(): Set[String] = getLinks("alternate")
}

trait ExtractorFactory extends Function[String, Extractor]

sealed abstract class ExtractorProviderType(val name: String)

object ExtractorProviderTypes {
  case object YOUTUBE extends ExtractorProviderType("youtube")
  case object GITHUB extends ExtractorProviderType("github")
  case object LINKEDIN extends ExtractorProviderType("linkedin")
  case object LINKEDIN_ID extends ExtractorProviderType("linkedin_id")
  case object LINK_PROCESSING extends ExtractorProviderType("link_processing")
  case object SIMPLE_JSOUP extends ExtractorProviderType("simple_jsoup")
  val ALL: Seq[ExtractorProviderType] = Seq(YOUTUBE, GITHUB, LINKEDIN, LINKEDIN_ID, LINK_PROCESSING, SIMPLE_JSOUP)
}

abstract class ExtractorProvider extends PartialFunction[URI, Extractor]
