package com.keepit.scraper.extractor

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

  private[this] val endElemProcs: Map[String, (String, String, String)=>Unit] = Map(
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

class DehyphenatingTextOutputContentHandler(handler: ContentHandler) extends TextOutputContentHandler(handler) {

  private[this] val buf = new Array[Char](1000)
  private[this] var bufLen = 0
  private[this] var lastChar: Char = 0
  private[this] var hyphenation = false

  private[this] def flushBuf() {
    if (bufLen > 0) {
      handler.characters(buf, 0, bufLen)
      bufLen = 0
    }
  }

  private[this] def emptyBuf() {
    bufLen = 0
  }

  private[this] def addToBuf(c: Char) {
    buf(bufLen) = c
    bufLen += 1
    lastChar = c
  }

  private[this] def isBufFull: Boolean = (bufLen >= buf.length)

  override def characters(ch: Array[Char], start: Int, length: Int) {
    var ptr = start
    var end = start + length
    while (ptr < end) {
      val c = ch(ptr)
      if (hyphenation) {
        // if newline or space, this may be a hyphenation
        if (c == '\n' || c.isSpaceChar) {
          // but, if the buffer is full, we give up.
          if (isBufFull) {
            flushBuf()
            hyphenation = false
          }
        } else {
          // if the current char is not a letter, this was not a hyphenation, flush buffered chars,
          // otherwise, empty the buffer (dehyphenation)
          if (!c.isLetter) flushBuf() else emptyBuf()
          hyphenation = false
        }
      } else {
        if (c == '-' && lastChar.isLetter) {
          flushBuf()
          hyphenation = true
        } else if (isBufFull) {
          flushBuf()
        }
      }
      addToBuf(c)
      ptr += 1
    }
  }

  override def ignorableWhitespace(ch: Array[Char], start: Int, length: Int) {
    characters(ch, start, length);
  }
}

