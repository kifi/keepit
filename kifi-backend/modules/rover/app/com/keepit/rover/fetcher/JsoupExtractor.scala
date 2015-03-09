package com.keepit.rover.fetcher

import com.keepit.common.logging.Logging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import scala.collection.JavaConversions._

import scala.util.Try

class JsoupExtractor(doc: Document) extends Logging {

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

object JsoupExtractor {
  def parse(input: HttpInputStream, destinationUrl: String, charset: String = null): Try[JsoupExtractor] = {
    // null charset autodetects based on `http-equiv` meta tag and default to UTF-8, Parser defaults to HTML
    Try(Jsoup.parse(input, charset, destinationUrl)).map(new JsoupExtractor(_))
  }
}
