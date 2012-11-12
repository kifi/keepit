package com.keepit.scraper.extractor

import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.scraper.Scraper
import org.apache.tika.parser.html.BoilerpipeContentHandler
import org.apache.tika.sax.ContentHandlerDecorator
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler

object DefaultExtractorFactory extends ExtractorFactory {
  def isDefinedAt(uri: URI) = true
      def apply(uri: URI) = new DefaultExtractor(uri.toString, Scraper.maxContentChars)
}

class DefaultExtractor(url: String, maxContentChars: Int) extends TikaBasedExtractor(url, maxContentChars) with Logging {
  protected def getContentHandler = new BoilerSafeContentHandler(new BoilerpipeContentHandler(output))
}

// To prevent Boilerpipe blowing up we need this
class BoilerSafeContentHandler(handler: ContentHandler) extends ContentHandlerDecorator(handler) {
  
  var inAnchor = false
  
  override def startElement(uri: String, localName: String, qName: String, atts: Attributes) {
    localName.toLowerCase() match {
      case "a" => //nested anchor tags blow up Boilerpipe. so we close it if one is already open
        if (inAnchor) endElement(uri, localName, qName)
        super.startElement(uri, localName, qName, atts)
        inAnchor = true
      case _ => super.startElement(uri, localName, qName, atts)
    }
  }
  
  override def endElement(uri: String, localName: String, qName: String) {
    localName.toLowerCase() match {
      case "a" => if (inAnchor) {
        super.endElement(uri, localName, qName)
        inAnchor = false
      }
      case _ => super.endElement(uri, localName, qName)
    }
  }
}
