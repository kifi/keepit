package com.keepit.scraper.extractor

import com.keepit.common.logging.Logging
import com.keepit.scraper.HttpInputStream

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.regex.Pattern

abstract class JsoupBasedExtractor(url: String, maxContentChars: Int) extends Extractor with Logging {
  protected var doc: Document = null

  def parse(doc: Document): String

  def process(input: HttpInputStream){
    try {
      doc = Jsoup.parse(input, null, url) // null charset autodetects based on `http-equiv` meta tag and default to UTF-8, Parser defaults to HTML
    } catch {
      case e: Throwable => log.error("Jsoup extraction failed: ", e)
    }
  }

  def getContent() = {
    val content = parse(doc)
    if (content.length > maxContentChars) log.warn(s"max number of characters reached: ${url}")
    content.take(maxContentChars)
  }

  def getMetadata(name: String): Option[String] = toOption(doc.select("meta[name=" + name + "]").attr("content"))
                                          .orElse(toOption(doc.select("meta[property=" + name + "]").attr("content")))

  def getLink(name: String): Option[String] = toOption(doc.select("link[ref=" + name + "]").attr("href"))

  private def toOption(str: String): Option[String] = if (str == null || str.isEmpty) None else Some(str)

  def getKeywords(): Option[String] = getMetadata("keywords")

  private[extractor] def replace(text: String, replacements: (String, String)*) = {
    val replacement = replacements.toMap.withDefault(identity)
    val regex = replacement.keysIterator.map(Pattern.quote).mkString("|").r
    regex.replaceAllIn(text, m => replacement(m.matched))
  }
}

