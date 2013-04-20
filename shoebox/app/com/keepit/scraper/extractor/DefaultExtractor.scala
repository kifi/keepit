package com.keepit.scraper.extractor

import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.scraper.Scraper
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
  protected def getContentHandler = new DefaultContentHandler(new BoilerpipeContentHandler(output))
}

class DefaultContentHandler(handler: ContentHandler) extends ContentHandlerDecorator(handler) {

  var inAnchor = false
  var inOption = false

  override def startElement(uri: String, localName: String, qName: String, atts: Attributes) {
    localName.toLowerCase() match {
      case "a" => //nested anchor tags blow up Boilerpipe. so we close it if one is already open
        if (inAnchor) endElement(uri, localName, qName)
        super.startElement(uri, localName, qName, atts)
        inAnchor = true
      case "option" => //we will ignore text in options (drop down menu, etc.)
        inOption = true
      case _ => super.startElement(uri, localName, qName, atts)
    }
  }

  override def endElement(uri: String, localName: String, qName: String) {
    localName.toLowerCase() match {
      case "a" => if (inAnchor) {
        super.endElement(uri, localName, qName)
        inAnchor = false
      }
      case "option" =>
        inOption = false
      case _ => super.endElement(uri, localName, qName)
    }
  }

  override def characters(ch: Array[Char], start: Int, length: Int) {
    //ignore text options (drop down menu, etc.)
    if (!inOption) {
      handler.characters(ch, start, length)
    }
  }
}
