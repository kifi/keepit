package com.keepit.rover.extractor.tika

import com.keepit.common.logging.Logging
import com.keepit.rover.extractor.utils.URITokenizer
import org.apache.tika.metadata.{ HttpHeaders, Metadata }
import org.apache.tika.parser.html.BoilerpipeContentHandler
import org.apache.tika.sax.{ WriteOutContentHandler, ContentHandlerDecorator }
import org.xml.sax.Attributes
import play.api.http.MimeTypes

import scala.collection.mutable

object MainContentHandler {
  val maxContentChars = 100000 // 100K chars
  def apply(metadata: Metadata, url: String): MainContentHandler = {
    val output = new WriteOutContentHandler(maxContentChars)
    new MainContentHandler(maxContentChars, output, metadata, url)
  }
}

class MainContentHandler(maxContentChars: Int, output: WriteOutContentHandler, val metadata: Metadata, val url: String) extends ContentHandlerDecorator(output) with Logging {

  def getContent(): String = output.toString

  private[this] var charsCount = 0
  private[this] var maxContentCharsLimitReached = false

  private[this] var keywordValidatorContentHandler: Option[KeywordValidatorContentHandler] = None

  def getKeywords: Option[Seq[String]] = keywordValidatorContentHandler.map { _.keywords }

  val links = new mutable.HashMap[String, mutable.Set[String]] with mutable.MultiMap[String, String] // todo(Léo): use Tika's LinkHandler instead

  def isWriteLimitReached(t: Throwable): Boolean = output.isWriteLimitReached(t)

  override def startDocument() {
    // enable boilerpipe only for HTML
    Option(metadata.get(HttpHeaders.CONTENT_TYPE)).foreach { contentType =>
      if (contentType startsWith MimeTypes.HTML) {
        val keywordValidator = new KeywordValidator(URITokenizer.getTokens(url))
        keywordValidatorContentHandler = Some(
          new KeywordValidatorContentHandler(keywordValidator, new BoilerpipeContentHandler(new TextOutputContentHandler(output)))
        )
        setContentHandler(keywordValidatorContentHandler.get)
      } else {
        setContentHandler(new DehyphenatingTextOutputContentHandler(output))
      }
    }
    super.startDocument()
  }

  // anchor tag
  private[this] var inAnchor = false

  private def startAnchor(uri: String, localName: String, qName: String, atts: Attributes) = {
    //nested anchor tags blow up Boilerpipe. so we close it if one is already open
    if (inAnchor) super.endElement(uri, localName, qName)
    super.startElement(uri, localName, qName, atts)
    inAnchor = true
  }
  private def endAnchor(uri: String, localName: String, qName: String) = {
    if (inAnchor) super.endElement(uri, localName, qName)
    inAnchor = false
  }

  // link tag
  private def startLink(uri: String, localName: String, qName: String, atts: Attributes): Unit = {
    super.startElement(uri, localName, qName, atts)
    val rel = atts.getValue("rel")
    val href = atts.getValue("href")

    if (rel != null && href != null) links.addBinding(rel, href)
  }

  // option tag
  private[this] var inOption = false
  private def startOption(uri: String, localName: String, qName: String, atts: Attributes) = {
    inOption = true
  }
  private def endOption(uri: String, localName: String, qName: String) = {
    inOption = false
  }

  private val startElemProcs: Map[String, (String, String, String, Attributes) => Unit] = Map(
    "a" -> startAnchor,
    "option" -> startOption,
    "link" -> startLink
  )

  private val endElemProcs: Map[String, (String, String, String) => Unit] = Map(
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
    // skip when max reached && ignore text options (drop down menu, etc.)
    if (!(maxContentCharsLimitReached || inOption)) {
      if ((charsCount + length) < maxContentChars) {
        charsCount += length
        super.characters(ch, start, length)
      } else {
        log.warn(s"maxContentCharsLimit($maxContentChars) reached for $url; skip rest of document.")
        maxContentCharsLimitReached = true
      }
    }
  }
}
