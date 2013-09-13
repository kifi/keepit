package com.keepit.scraper.extractor

import com.keepit.scraper.HttpInputStream
import com.keepit.common.net.URI

object Extractor {
  val factories = Seq(
    YoutubeExtractorProvider,
    GithubExtractorProvider,
    DefaultExtractorProvider
  )
}
trait Extractor {
  def process(input: HttpInputStream): Unit
  def getContent(): String
  def getMetadata(name: String): Option[String]
  def getKeywords: Option[String] = None
}

abstract class ExtractorProvider extends PartialFunction[URI, Extractor]

