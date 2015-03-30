package com.keepit.rover.document.tika

import org.apache.tika.sax.ContentHandlerDecorator
import org.xml.sax.ContentHandler

class KeywordValidatorContentHandler(keywordValidator: KeywordValidator, handler: ContentHandler) extends ContentHandlerDecorator(handler) {

  override def startDocument(): Unit = {
    super.startDocument()
    keywordValidator.startDocument()
  }

  override def endDocument(): Unit = {
    super.endDocument()
    keywordValidator.endDocument()
  }

  override def characters(ch: Array[Char], start: Int, length: Int): Unit = {
    super.characters(ch, start, length)
    keywordValidator.characters(ch, start, length)
  }

  def keywords: Seq[String] = keywordValidator.keywords
}

