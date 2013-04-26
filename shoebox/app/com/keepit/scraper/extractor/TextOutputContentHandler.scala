package com.keepit.scraper.extractor

import org.apache.tika.sax.WriteOutContentHandler
import org.xml.sax.ContentHandler

class TextOutputContentHandler(maxContentChars: Int) extends WriteOutContentHandler(maxContentChars) {
  private[this] val newLine = Array('\n')

  // title tag
  private def endTitle(uri: String, localName: String, qName: String) = {
    characters(Array('\n'), 0, 1)
  }

  // p tag
  private def endP(uri: String, localName: String, qName: String) = {
    characters(Array('\n'), 0, 1)
  }

  val endElemProcs: Map[String, (String, String, String)=>Unit] = Map(
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
