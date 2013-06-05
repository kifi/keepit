package com.keepit.scraper.extractor

import com.keepit.common.logging.Logging
import com.keepit.scraper.HttpInputStream

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

abstract class JsoupBasedExtractor(url: String, maxContentChars: Int) extends Extractor with Logging {
  protected var doc: Document = null

  def parse(doc: Document): String

  def process(input: HttpInputStream){
    try {
      doc = Jsoup.parse(input, null, url) // null charset autodetects based on `http-equiv` meta tag and default to UTF-8
    } catch {
      case e: Throwable => log.error("Jsoup extraction failed: ", e)
    }
  }

  def getContent() = parse(doc).take(maxContentChars)

  def getMetadata(name: String) = Option(doc.select("meta[name=" + name + "]").select("content").first()) map (_.toString)
}

