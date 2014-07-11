package com.keepit.scraper.extractor

import com.keepit.common.logging.Logging
import com.keepit.scraper.ScraperConfig
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.html.BoilerpipeContentHandler
import org.apache.tika.parser.html.DefaultHtmlMapper
import org.apache.tika.parser.html.HtmlMapper
import org.apache.tika.sax
import org.apache.tika.sax.{ WriteOutContentHandler, ContentHandlerDecorator }
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler
import play.api.http.MimeTypes
import com.keepit.common.net.URI
import scala.collection.mutable

object DefaultExtractorProvider extends ExtractorProvider {
  def isDefinedAt(uri: URI) = true
  def apply(uri: URI) = apply(uri.toString)
  def apply(url: String) = new DefaultExtractor(url, ScraperConfig.maxContentChars, htmlMapper)
  def apply(uri: URI, maxContentChars: Int) = new DefaultExtractor(uri.toString, maxContentChars, htmlMapper)

  val htmlMapper = Some(new DefaultHtmlMapper {
    override def mapSafeElement(name: String) = {
      name.toLowerCase match {
        case "option" => "option"
        case _ => super.mapSafeElement(name)
      }
    }
  })
}

object DefaultExtractor {
  val specialRegex = """[,;:/]\s*""".r
  val spaceRegex = """\s+""".r
}

class DefaultExtractor(url: String, maxContentChars: Int, htmlMapper: Option[HtmlMapper]) extends TikaBasedExtractor(url, maxContentChars, htmlMapper) {
  private[this] val handler: DefaultContentHandler = new DefaultContentHandler(maxContentChars, output, metadata, url)

  protected def getContentHandler: ContentHandler = handler

  def getLinks(key: String): Set[String] = handler.links.getOrElse(key, Set.empty).toSet

  override def getKeywords(): Option[String] = {
    val str = (handler.getKeywords.map { _.mkString(", ") } ++ getValidatedMetaTagKeywords).mkString(" | ")
    if (str.length > 0) Some(str) else None
  }

  private def getValidatedMetaTagKeywords: Option[String] = {
    getMetadata("keywords").flatMap { meta =>
      import DefaultExtractor._
      val phrases = specialRegex.split(meta).filter { _.length > 0 }.toSeq
      val allPhrases = phrases.foldLeft(phrases) { (phrases, onePhrase) => phrases ++ spaceRegex.split(onePhrase).filter { _.length > 0 }.toSeq }
      val validator = new KeywordValidator(allPhrases)

      validator.startDocument()

      getMetadata("title").foreach { title =>
        validator.characters(title.toCharArray)
        validator.break()
      }
      getMetadata("description").foreach { description =>
        validator.characters(description.toCharArray)
        validator.break()
      }
      handler.getKeywords.foreach { keywords => // keywords from URI path
        keywords.foreach { keyword =>
          validator.characters(keyword.toCharArray)
          validator.break()
        }
      }
      validator.characters(getContent().toCharArray) // content

      validator.endDocument()

      if (validator.coverage > 0.3d) Some(meta) else None
    }
  }
}

class DefaultContentHandler(maxContentChars: Int, handler: ContentHandler, metadata: Metadata, uri: String) extends ContentHandlerDecorator(handler) with Logging {

  var charsCount = 0
  var maxContentCharsLimitReached = false

  private[this] var keywordValidatorContentHandler: Option[KeywordValidatorContentHandler] = None

  def getKeywords: Option[Seq[String]] = keywordValidatorContentHandler.map { _.keywords }

  private[extractor] val links = new mutable.HashMap[String, mutable.Set[String]] with mutable.MultiMap[String, String]

  override def startDocument() {
    // enable boilerpipe only for HTML
    Option(metadata.get("Content-Type")).foreach { contentType =>
      if (contentType startsWith MimeTypes.HTML) {
        val keywordValidator = new KeywordValidator(URITokenizer.getTokens(uri))
        keywordValidatorContentHandler = Some(
          new KeywordValidatorContentHandler(keywordValidator, new BoilerpipeContentHandler(new TextOutputContentHandler(handler)))
        )
        setContentHandler(keywordValidatorContentHandler.get)
      } else {
        setContentHandler(new DehyphenatingTextOutputContentHandler(handler))
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
  private def startLink(uri: String, localName: String, qName: String, atts: Attributes) = {
    super.startElement(uri, localName, qName, atts)
    val rel = atts.getValue("rel")
    val href = atts.getValue("href")
    links.addBinding(rel, href)
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
        log.warn(s"maxContentCharsLimit($maxContentChars) reached for $uri; skip rest of document.")
        maxContentCharsLimitReached = true
      }
    }
  }
}
