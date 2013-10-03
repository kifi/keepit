package com.keepit.scraper.extractor

import com.keepit.common.logging.Logging
import com.keepit.scraper.HttpInputStream

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

abstract class JsoupBasedExtractor(url: String, maxContentChars: Int, parser: Option[Parser] = None) extends Extractor with Logging {
  protected var doc: Document = null

  def parse(doc: Document): String

  def process(input: HttpInputStream){
    try {
      doc = parser.map(Jsoup.parse(input, null, url, _)).getOrElse(Jsoup.parse(input, null, url)) // null charset autodetects based on `http-equiv` meta tag and default to UTF-8, Parser defaults to HTML
    } catch {
      case e: Throwable => log.error("Jsoup extraction failed: ", e)
    }
  }

  def getContent() = {
    val content = parse(doc)
    if (content.length > maxContentChars) log.warn(s"max number of characters reached: ${url}")
    content.take(maxContentChars)
  }

  def getMetadata(name: String) = Option(doc.select("meta[name=" + name + "]").select("content").first()) map (_.toString)
}

