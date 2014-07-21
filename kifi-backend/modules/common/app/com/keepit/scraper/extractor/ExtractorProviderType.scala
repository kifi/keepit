package com.keepit.scraper.extractor

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