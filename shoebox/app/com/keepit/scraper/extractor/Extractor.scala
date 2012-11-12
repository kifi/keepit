package com.keepit.scraper.extractor

import com.keepit.common.net.URI
import java.io.InputStream

object Extractor {
  val factories = Seq(
    YoutubeExtractorFactory,
    DefaultExtractorFactory
  )
}
trait Extractor {
  def process(input: InputStream): Unit
  def getContent(): String
  def getMetadata(name: String): Option[String]
}

abstract class ExtractorFactory extends PartialFunction[URI, Extractor]

