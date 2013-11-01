package com.keepit.scraper.extractor

import com.keepit.scraper.HttpInputStream

trait Extractor {
  def process(input: HttpInputStream): Unit
  def getContent(): String
  def getMetadata(name: String): Option[String]
  def getKeywords(): Option[String]
}

trait ExtractorFactory extends Function[String, Extractor]
