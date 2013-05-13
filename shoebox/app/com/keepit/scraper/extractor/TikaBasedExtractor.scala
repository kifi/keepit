package com.keepit.scraper.extractor

import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.scraper.HttpInputStream
import com.keepit.scraper.Scraper
import org.apache.tika.detect.DefaultDetector
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.html.HtmlMapper
import org.apache.tika.parser.html.HtmlParser
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.Parser
import org.apache.tika.sax.ContentHandlerDecorator
import org.apache.tika.sax.WriteOutContentHandler
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler
import org.xml.sax.SAXException

abstract class TikaBasedExtractor(url: String, maxContentChars: Int, htmlMapper: Option[HtmlMapper]) extends Extractor with Logging {

  protected val output = new WriteOutContentHandler(maxContentChars)

  protected val metadata = new Metadata()

  protected def getContentHandler: ContentHandler

  protected def getParser(contentType: Option[String]): Parser = {
    contentType.flatMap{ contentType =>
      if (contentType startsWith "text/html") Some(new HtmlParser())
      else None
    }.getOrElse{
      new AutoDetectParser(new DefaultDetector())
    }
  }

  protected def getHtmlMapper: Option[HtmlMapper] = htmlMapper

  def process(input: HttpInputStream){
    val context = new ParseContext()
    var parser = getParser(input.getContentType)
    val contentHandler = getContentHandler
    context.set(classOf[Parser], parser)
    getHtmlMapper.foreach(mapper => context.set(classOf[HtmlMapper], mapper))

    try {
      parser.parse(input, contentHandler, metadata, context)
    } catch {
      case e: Throwable =>
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
