package com.keepit.scraper.extractor

import com.keepit.common.logging.Logging
import com.keepit.common.net.Host
import com.keepit.common.net.URI
import com.keepit.scraper.Scraper
import org.apache.tika.sax.ContentHandlerDecorator
import org.apache.tika.parser.html.DefaultHtmlMapper
import org.apache.tika.parser.html.HtmlMapper
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler

object YoutubeExtractorFactory extends ExtractorFactory {
  def isDefinedAt(uri: URI) = {
    uri match {
      case URI(_, _, Some(Host("com", "youtube", _*)), _, Some(path), Some(query), _) =>
        path.endsWith("/watch") && query.containsParam("v")
      case _ => false
    }
  }
  def apply(uri: URI) = new YoutubeExtractor(uri.toString, Scraper.maxContentChars, htmlMapper)

  private val htmlMapper = Some(
    new DefaultHtmlMapper {
      override def mapSafeElement(name: String) = {
        name.toLowerCase match {
          case "div" => "div"
          case _ =>super.mapSafeElement(name)
        }
      }
      override def mapSafeAttribute(elementName: String, attributeName: String) = {
        (elementName.toLowerCase, attributeName.toLowerCase) match {
          case ("div", "class") => "class"
          case _ => super.mapSafeAttribute(elementName, attributeName)
        }
      }
    }
  )
}

class YoutubeExtractor(url: String, maxContentChars: Int, htmlMapper: Option[HtmlMapper]) extends TikaBasedExtractor(url, maxContentChars, htmlMapper) {
  protected def getContentHandler = new YoutubeHandler(output)

  override def getContent() = {
    val buf = new StringBuilder
    buf.append(getMetadata("title").getOrElse(""))
    buf.append(getMetadata("description").getOrElse(""))
    buf.append(output.toString)
    buf.toString
  }
}

class YoutubeHandler(handler: ContentHandler) extends ContentHandlerDecorator(handler) {

  var inCommentText = Int.MaxValue
  var nestLevel = 0

  override def startElement(uri: String, localName: String, qName: String, atts: Attributes) {
    nestLevel += 1
    localName match {
      case "div" =>
        val classIdx = atts.getIndex("class")
        if (classIdx >= 0) {
          atts.getValue(classIdx) match {
            case "comment-text" =>
              if (inCommentText == Int.MaxValue) inCommentText = nestLevel
            case _ =>
          }
        }
        super.startElement(uri, localName, qName, atts)
      case _ => super.startElement(uri, localName, qName, atts)
    }
  }

  override def endElement(uri: String, localName: String, qName: String) {
    nestLevel -= 1
    localName match {
      case "div" =>
        super.endElement(uri, localName, qName)
        if (nestLevel <= inCommentText) inCommentText = Int.MaxValue
      case _ => super.endElement(uri, localName, qName)
    }
  }

  override def characters(ch: Array[Char], start: Int, length: Int) {
    if (inCommentText <= nestLevel) {
      handler.characters(ch, start, length)
    }
  }
}
