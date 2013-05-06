package com.keepit.scraper.extractor

import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.scraper.Scraper
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.html.BoilerpipeContentHandler
import org.apache.tika.parser.html.DefaultHtmlMapper
import org.apache.tika.parser.html.HtmlMapper
import org.apache.tika.sax.ContentHandlerDecorator
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler

object DefaultExtractorFactory extends ExtractorFactory {
  def isDefinedAt(uri: URI) = true
  def apply(uri: URI) = new DefaultExtractor(uri.toString, Scraper.maxContentChars, htmlMapper)
  def apply(url: String) = new DefaultExtractor(url, Scraper.maxContentChars, htmlMapper)

  private val htmlMapper = Some(new DefaultHtmlMapper {
    override def mapSafeElement(name: String) = {
      name.toLowerCase match {
        case "option" => "option"
        case _ =>super.mapSafeElement(name)
      }
    }
  })
}

class DefaultExtractor(url: String, maxContentChars: Int, htmlMapper: Option[HtmlMapper]) extends TikaBasedExtractor(url, maxContentChars, htmlMapper) {
  protected def getContentHandler = new DefaultContentHandler(output, metadata)
}

class DefaultContentHandler(handler: ContentHandler, metadata: Metadata) extends ContentHandlerDecorator(handler) {

  override def startDocument() {
    // enable boilerpipe only for HTML
    Option(metadata.get("Content-Type")).foreach{ contentType =>
      if (contentType startsWith "text/html") {
        setContentHandler(new BoilerpipeContentHandler(handler))
      } else {
        setContentHandler(new DehyphenatingContentHandler(handler))
      }
    }
    super.startDocument()
  }

  // anchor tag
  private[this] var inAnchor = false
  private def startAnchor(uri: String, localName: String, qName: String, atts: Attributes) = {
    //nested anchor tags blow up Boilerpipe. so we close it if one is already open
    if (inAnchor) endElement(uri, localName, qName)
    super.startElement(uri, localName, qName, atts)
    inAnchor = true
  }
  private def endAnchor(uri: String, localName: String, qName: String) = {
    super.endElement(uri, localName, qName)
    inAnchor = false
  }

  // option tag
  private[this] var inOption = false
  private def startOption(uri: String, localName: String, qName: String, atts: Attributes) = {
    inOption = true
  }
  private def endOption(uri: String, localName: String, qName: String) = {
    inOption = false
  }

  private val startElemProcs: Map[String, (String, String, String, Attributes)=>Unit] = Map(
    "a" -> startAnchor,
    "option" -> startOption
  )
  private val endElemProcs: Map[String, (String, String, String)=>Unit] = Map(
    "a" -> endAnchor,
    "option" -> endOption
  )

  override def startElement(uri: String, localName: String, qName: String, atts: Attributes) {
    startElemProcs.get(localName.toLowerCase()) match {
      case Some(proc) => proc(uri, localName, qName, atts)
      case None => super.startElement(uri, localName, qName, atts)
    }
  }

  override def endElement(uri: String, localName: String, qName: String) {
    endElemProcs.get(localName.toLowerCase()) match {
      case Some(proc) => proc(uri, localName, qName)
      case None => super.endElement(uri, localName, qName)
    }
  }

  override def characters(ch: Array[Char], start: Int, length: Int) {
    //ignore text options (drop down menu, etc.)
    if (!inOption) {
      super.characters(ch, start, length)
    }
  }
}

class DehyphenatingContentHandler(handler: ContentHandler) extends ContentHandlerDecorator(handler) {

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

  override def startElement(uri: String, localName: String, qName: String, atts: Attributes) {
    flushBuf()
    hyphenation = false
    super.startElement(uri, localName, qName, atts)
  }

  override def endElement(uri: String, localName: String, qName: String) {
    flushBuf()
    hyphenation = false
    super.endElement(uri, localName, qName)
  }

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

