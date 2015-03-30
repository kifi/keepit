package com.keepit.rover.document.tika

import org.apache.tika.sax.ContentHandlerDecorator
import org.xml.sax.ContentHandler

class TextOutputContentHandler(handler: ContentHandler) extends ContentHandlerDecorator(handler) {

  private[this] val newLine = Array('\n')

  // title tag
  private def endTitle(uri: String, localName: String, qName: String) = {
    characters(newLine, 0, 1)
  }

  // p tag
  private def endP(uri: String, localName: String, qName: String) = {
    characters(newLine, 0, 1)
  }

  private[this] val endElemProcs: Map[String, (String, String, String) => Unit] = Map(
    "title" -> endTitle,
    "p" -> endP
  )

  override def endElement(uri: String, localName: String, qName: String) {
    endElemProcs.get(localName.toLowerCase()) match {
      case Some(proc) => proc(uri, localName, qName)
      case None => super.endElement(uri, localName, qName)
    }
  }
}

