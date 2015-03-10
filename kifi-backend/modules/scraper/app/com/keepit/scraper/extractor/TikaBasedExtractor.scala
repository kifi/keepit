package com.keepit.scraper.extractor

import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.scraper.DeprecatedHttpInputStream
import org.apache.tika.detect.DefaultDetector
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.HttpHeaders
import org.apache.tika.parser.html.HtmlMapper
import org.apache.tika.parser.html.HtmlParser
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.Parser
import org.apache.tika.sax.WriteOutContentHandler
import org.xml.sax.ContentHandler
import play.api.http.MimeTypes
import org.apache.tika.io.{ TikaInputStream, TemporaryResources }

import scala.util.{ Success, Failure, Try }

abstract class TikaBasedExtractor(url: URI, maxContentChars: Int, htmlMapper: Option[HtmlMapper]) extends Extractor with Logging {

  protected val output = new WriteOutContentHandler(maxContentChars)

  protected val metadata = new Metadata()

  protected def getContentHandler: ContentHandler

  protected def getParser(contentType: Option[String]): Parser = {
    contentType.flatMap { contentType =>
      if (contentType startsWith MimeTypes.HTML) Some(new HtmlParser())
      else None
    }.getOrElse {
      new AutoDetectParser(new DefaultDetector())
    }
  }

  protected def getHtmlMapper: Option[HtmlMapper] = htmlMapper

  def process(input: DeprecatedHttpInputStream) {
    val context = new ParseContext()
    var parser = getParser(input.getContentType())
    val contentHandler = getContentHandler
    context.set(classOf[Parser], parser)
    getHtmlMapper.foreach(mapper => context.set(classOf[HtmlMapper], mapper))

    input.getContentType().foreach { metadata.set(HttpHeaders.CONTENT_TYPE, _) }

    val tmp = new TemporaryResources()
    // see http://tika.apache.org/1.3/api/org/apache/tika/io/TikaInputStream.html#get(java.io.InputStream, org.apache.tika.io.TemporaryResources)
    // and http://stackoverflow.com/questions/14280128/tika-could-not-delete-temporary-files
    val stream = TikaInputStream.get(input, tmp)
    try {
      parser.parse(stream, contentHandler, metadata, context)
    } catch {
      case e: Throwable =>
        // check if we hit our content size limit (maxContentChars)
        if (output.isWriteLimitReached(e))
          log.warn("max number of characters reached: " + url)
        else
          log.error("extraction failed: ", e)
    } finally {
      try {
        stream.close()
      } catch {
        case e: Exception => log.error(s"error closing Tika stream of content type: ${input.httpContentType}", e)
      }
    }
  }

  private[this] lazy val _content = output.toString

  def getContent() = _content

  def getMetadata(name: String): Option[String] = {
    def initCap(str: String) = {
      if (str.length > 0) {
        str.substring(0, 1).toUpperCase + str.substring(1).toLowerCase
      } else {
        str
      }
    }

    // take longer one if there are multiple values
    metadata.getValues(name).sortBy(_.size).lastOption
      .orElse(metadata.getValues(name.toLowerCase).sortBy(_.size).lastOption)
      .orElse(metadata.getValues(name.toUpperCase).sortBy(_.size).lastOption)
      .orElse(metadata.getValues(initCap(name)).sortBy(_.size).lastOption)
  }

}
