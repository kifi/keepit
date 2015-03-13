package com.keepit.scraper.extractor

import com.keepit.common.net.URI
import com.keepit.rover.fetcher.{ FetchResult, HttpInputStream }

import scala.collection.JavaConversions._

import com.keepit.common.logging.Logging

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import scala.util.Try

abstract class JsoupBasedExtractor(url: URI, maxContentChars: Int) extends Extractor with Logging {
  protected var doc: Document = null // why!?

  def parse(doc: Document): String

  def process(result: FetchResult) {
    try {
      doc = Jsoup.parse(result.content.get, null, url.toString()) // null charset autodetects based on `http-equiv` meta tag and default to UTF-8, Parser defaults to HTML
    } catch {
      case e: Throwable => log.error("Jsoup extraction failed: ", e)
    }
  }

  def getContent() = {
    if (doc != null) {
      val content = parse(doc)
      if (content.length > maxContentChars) log.warn(s"max number of characters reached: ${url}")
      content.take(maxContentChars)
    } else {
      ""
    }
  }

  def getMetadata(name: String): Option[String] = {
    if (name.toLowerCase == "title") {
      toOption(doc.select("head title").text)
    } else {
      None
    }
      .orElse(toOption(doc.select("meta[name=" + name + "]").attr("content")))
      .orElse(toOption(doc.select("meta[property=" + name + "]").attr("content")))
  }

  def getLinks(name: String): Set[String] = {
    val urls = doc.select("link[ref=" + name + "]").iterator() map { e => e.attr("href") }
    urls filterNot { str => str == null || str.isEmpty } toSet
  }

  private def toOption(unsafe: => String): Option[String] = {
    Try(Option(unsafe)).toOption.flatten.filter(_.nonEmpty)
  }

  def getKeywords(): Option[String] = getMetadata("keywords")
}
