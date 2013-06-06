package com.keepit.scraper.extractor

import com.keepit.common.net.URI
import com.keepit.scraper.HttpInputStream

object Extractor {
  val factories = Seq(
    YoutubeExtractorFactory,
    GithubExtractorFactory,
    DefaultExtractorFactory
  )
}
trait Extractor {
  def process(input: HttpInputStream): Unit
  def getContent(): String
  def getMetadata(name: String): Option[String]
}

abstract class ExtractorFactory extends PartialFunction[URI, Extractor]

