package com.keepit.scraper.extractor

import com.keepit.rover.extractor.tika.{ KeywordValidator, DefaultContentHandler }
import com.keepit.scraper.ScraperConfig
import org.apache.tika.parser.html.DefaultHtmlMapper
import org.apache.tika.parser.html.HtmlMapper
import org.xml.sax.ContentHandler
import com.keepit.common.net.URI

object DefaultExtractorProvider extends ExtractorProvider {
  def isDefinedAt(uri: URI) = true
  def apply(uri: URI) = new DefaultExtractor(uri, ScraperConfig.maxContentChars, htmlMapper)
  def apply(uri: URI, maxContentChars: Int) = new DefaultExtractor(uri, maxContentChars, htmlMapper)

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

class DefaultExtractor(url: URI, maxContentChars: Int, htmlMapper: Option[HtmlMapper]) extends TikaBasedExtractor(url, maxContentChars, htmlMapper) {
  private[this] val handler: DefaultContentHandler = new DefaultContentHandler(maxContentChars, output, metadata, url.toString())

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

