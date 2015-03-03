package com.keepit.scraper.extractor

import com.keepit.common.net.URI

import scala.collection.JavaConversions._

import com.keepit.common.logging.Logging
import com.keepit.scraper.HttpInputStream

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.regex.Pattern

import scala.util.Try

abstract class JsoupBasedExtractor(url: URI, maxContentChars: Int) extends Extractor with Logging {
  protected var doc: Document = null

  def parse(doc: Document): String

  def process(input: HttpInputStream) {
    try {
      doc = Jsoup.parse(input, null, url.toString()) // null charset autodetects based on `http-equiv` meta tag and default to UTF-8, Parser defaults to HTML
    } catch {
      case e: Throwable => log.error("Jsoup extraction failed: ", e)
    }
  }

  def getContent() = {
    val content = parse(doc)
    if (content.length > maxContentChars) log.warn(s"max number of characters reached: ${url}")
    content.take(maxContentChars)
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
    Try(Option(unsafe)).toOption.flatten.map {
      case o if o.isEmpty => None
      case o => Some(o)
    }.flatten
  }

  def getKeywords(): Option[String] = getMetadata("keywords")

  private[extractor] def replace(text: String, replacements: (String, String)*) = {
    val replacement = replacements.toMap.withDefault(identity)
    val regex = replacement.keysIterator.map(Pattern.quote).mkString("|").r
    regex.replaceAllIn(text, m => replacement(m.matched))
  }
}

