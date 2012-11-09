package com.keepit.scraper.extractor

import com.keepit.common.logging.Logging
import org.apache.tika.detect.DefaultDetector
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.html.BoilerpipeContentHandler
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.Parser
import org.apache.tika.sax.ContentHandlerDecorator
import org.apache.tika.sax.WriteOutContentHandler
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler
import org.xml.sax.SAXException
import java.io.InputStream
import org.apache.tika.parser.html.HtmlMapper

trait Extractor {
  def process(input: InputStream): Unit
  def getContent(): String
  def getMetadata(name: String): Option[String]
}

abstract class TikaBasedExtractor(url: String, maxContentChars: Int) extends Extractor with Logging {
  
  protected val output = new WriteOutContentHandler(maxContentChars)
  protected val metadata = new Metadata()
  
  protected def getContentHandler: ContentHandler
  protected def getParser: Parser = new AutoDetectParser(new DefaultDetector())
  protected def getHtmlMapper: Option[HtmlMapper] = None
  
  def process(input: InputStream){
    val context = new ParseContext()
    var parser = getParser
    val contentHandler = getContentHandler
    context.set(classOf[Parser], parser)
    getHtmlMapper.foreach(mapper => context.set(classOf[HtmlMapper], mapper))
    
    try {
      parser.parse(input, contentHandler, metadata, context)
    } catch {
      case e =>
        // check if we hit our content size limit (maxContentChars)
        if (output.isWriteLimitReached(e))
          log.warn("max number of characters reached: " + url)
        else
          log.error("extraction failed: ", e)
    }
  }
  
  def getContent() = output.toString
  
  def getMetadata(name: String) = Option(metadata.get(name))
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
