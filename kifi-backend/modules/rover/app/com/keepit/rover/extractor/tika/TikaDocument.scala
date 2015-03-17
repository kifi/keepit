package com.keepit.rover.extractor.tika

import com.keepit.common.logging.Logging
import com.keepit.rover.extractor.FetchedDocument
import com.keepit.rover.fetcher.HttpInputStream
import org.apache.tika.detect.DefaultDetector
import org.apache.tika.io.{ TemporaryResources, TikaInputStream }
import org.apache.tika.metadata.{ HttpHeaders, Metadata }
import org.apache.tika.parser.html.{ HtmlMapper, HtmlParser }
import org.apache.tika.parser.{ AutoDetectParser, ParseContext, Parser }
import org.apache.tika.sax.{ Link, LinkContentHandler, TeeContentHandler }
import org.xml.sax.ContentHandler
import play.api.http.MimeTypes
import com.keepit.common.core._
import scala.collection.JavaConversions._

class TikaDocument(
    metadata: Metadata,
    val content: String,
    val keywords: Seq[String],
    val links: Seq[Link]) extends FetchedDocument {

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

  def getLinks(rel: String): Set[String] = links.collect { case link if link.getRel == rel => link.getUri }.toSet

  def getTitle: Option[String] = getMetadata("title")
}

object TikaDocument extends Logging {

  def parse(input: HttpInputStream, destinationUrl: String, contentType: Option[String]): TikaDocument = {
    val metadata = new Metadata()
    contentType.foreach { metadata.set(HttpHeaders.CONTENT_TYPE, _) }
    val mainHandler = MainContentHandler(metadata, destinationUrl)
    val linkHandler = new LinkContentHandler()
    parseTo(input)(mainHandler, linkHandler)
    new TikaDocument(metadata, mainHandler.getContent(), mainHandler.getKeywords getOrElse Seq(), linkHandler.getLinks)
  }

  private def parseTo(input: HttpInputStream)(mainHandler: MainContentHandler, moreHandlers: ContentHandler*): Unit = {
    val handler = {
      val allHandlers = Seq(mainHandler) ++ moreHandlers
      new TeeContentHandler(allHandlers: _*)
    }
    val contentType = Option(mainHandler.metadata.get(HttpHeaders.CONTENT_TYPE))
    val parser = getParser(contentType)
    val context = getParserContext(parser)
    val tmp = new TemporaryResources()
    // see http://tika.apache.org/1.3/api/org/apache/tika/io/TikaInputStream.html#get(java.io.InputStream, org.apache.tika.io.TemporaryResources)
    // and http://stackoverflow.com/questions/14280128/tika-could-not-delete-temporary-files
    val stream = TikaInputStream.get(input, tmp)
    try {
      parser.parse(stream, handler, mainHandler.metadata, context)
    } catch {
      case e: Throwable =>
        // check if we hit our content size limit (maxContentChars)
        if (mainHandler.isWriteLimitReached(e))
          log.warn("max number of characters reached: " + mainHandler.url)
        else
          log.error("extraction failed: ", e)
    } finally {
      try {
        stream.close()
      } catch {
        case e: Exception => log.error(s"error closing Tika stream of content type: ${contentType}", e)
      }
    }
  }

  private def getParser(contentType: Option[String]): Parser = {
    contentType.flatMap { contentType =>
      if (contentType startsWith MimeTypes.HTML) Some(new HtmlParser())
      else None
    }.getOrElse {
      new AutoDetectParser(new DefaultDetector())
    }
  }

  private def getParserContext(parser: Parser, htmlMapper: Option[HtmlMapper] = HtmlMappers.default): ParseContext = {
    new ParseContext() tap { context =>
      context.set(classOf[Parser], parser)
      htmlMapper.foreach(mapper => context.set(classOf[HtmlMapper], mapper))
    }
  }
}
