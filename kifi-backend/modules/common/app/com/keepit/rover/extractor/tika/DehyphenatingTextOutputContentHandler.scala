package com.keepit.rover.extractor.tika

import org.xml.sax.ContentHandler

class DehyphenatingTextOutputContentHandler(handler: ContentHandler) extends TextOutputContentHandler(handler) {

  private[this] val buf = new Array[Char](1000)
  private[this] var bufLen = 0
  private[this] var lastChar: Char = 0
  private[this] var hyphenFound = false
  private[this] var newlineFound = false

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
    val end = start + length
    while (ptr < end) {
      val c = ch(ptr)
      if (hyphenFound) {
        // if newline or space, this may be a hyphenation
        if (c == '\n' || c.isSpaceChar) {
          // but, if the buffer is full, we give up.
          if (isBufFull) {
            flushBuf()
            hyphenFound = false
            newlineFound = false
          } else if (c == '\n') {
            newlineFound = true
          }
        } else {
          // if the current char is not a letter and there was no newline between hyphen and this char,
          // this is not a hyphenation, flush buffered chars
          // otherwise, empty the buffer (dehyphenation)
          if (!c.isLetter || !newlineFound) flushBuf() else emptyBuf()
          hyphenFound = false
          newlineFound = false
        }
      } else {
        if (c == '-' && lastChar.isLetter) {
          flushBuf()
          hyphenFound = true
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
