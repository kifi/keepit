package com.keepit.rover.extractor

import com.keepit.common.logging.Logging
import com.keepit.rover.fetcher.HttpInputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import scala.collection.JavaConversions._
import scala.util.Try

case class JsoupDocument(doc: Document) extends FetchedDocument with Logging {

  def getMetadata(name: String): Option[String] = {
    toOption(doc.select("meta[name=" + name + "]").attr("content")) orElse
      toOption(doc.select("meta[property=" + name + "]").attr("content"))
  }

  def getLinks(rel: String): Set[String] = {
    val urls = doc.select("link[ref=" + rel + "]").iterator() map { e => e.attr("href") }
    urls filterNot { str => str == null || str.isEmpty } toSet
  }

  def getTitle: Option[String] = toOption(doc.select("head title").text) orElse getMetadata("title")

  private def toOption(unsafe: => String): Option[String] = {
    Try(Option(unsafe)).toOption.flatten.filter(_.nonEmpty)
  }
}

object JsoupDocument {
  def parse(input: HttpInputStream, destinationUrl: String, charset: String = null): Try[JsoupDocument] = {
    // null charset autodetects based on `http-equiv` meta tag and default to UTF-8, Parser defaults to HTML
    Try(Jsoup.parse(input, charset, destinationUrl)).map(new JsoupDocument(_))
  }
}
