package com.keepit.scraper.extractor

import org.apache.tika.parser.html.HtmlMapper
import com.keepit.scraper.{Scraper, HttpFetcher}
import com.keepit.common.db.slick.Database
import com.keepit.model.UrlPatternRuleRepo
import com.google.inject.{Inject, Singleton}
import com.keepit.common.net.URI
import org.apache.tika.sax.{Link, LinkContentHandler, TeeContentHandler}
import org.xml.sax.ContentHandler
import scala.collection.JavaConversions._

class LinkProcessingExtractor(
  url: String,
  maxContentChars: Int,
  htmlMapper: Option[HtmlMapper],
  processLink: Link => Option[String],
  httpFetcher: HttpFetcher,
  db: Database,
  urlPatternRuleRepo: UrlPatternRuleRepo)
extends DefaultExtractor(url, maxContentChars, htmlMapper) {

  private val linkHandler = new LinkContentHandler()
  private val handler = new TeeContentHandler(super.getContentHandler, linkHandler)
  override def getContentHandler: ContentHandler = handler
  def getLinks(): Seq[Link] = linkHandler.getLinks

  private lazy val (linkedContent, linkedKeywords): (Seq[String], Seq[Option[String]]) = {
    val linkedContentWithKeywords = for {
      link <- getLinks()
      linkUrl <- processLink(link)
    } yield {
      log.info(s"Scraping additional content from link ${linkUrl} for url ${url}")
      val extractor = new DefaultExtractor(linkUrl, maxContentChars, htmlMapper)
      val proxy = db.readOnly {implicit session => urlPatternRuleRepo.getProxy(linkUrl) }
      httpFetcher.fetch(linkUrl, proxy = proxy)(extractor.process)
      (extractor.getContent(), extractor.getKeywords())
    }
    linkedContentWithKeywords.unzip
  }

  override def getContent(): String = (super.getContent() +: linkedContent).mkString("\n").take(maxContentChars)
  override def getKeywords(): Option[String] = {
    val keywords = (super.getKeywords() +: linkedKeywords).flatten.mkString(" | ")
    if (keywords.isEmpty) None else Some(keywords)
  }
}

@Singleton
class LinkProcessingExtractorProvider @Inject() (httpFetcher: HttpFetcher, db: Database, urlPatternRuleRepo: UrlPatternRuleRepo) extends ExtractorProvider {
  def isDefinedAt(uri: URI) = true
  def apply(uri: URI) = new LinkProcessingExtractor(uri.toString, Scraper.maxContentChars, DefaultExtractorProvider.htmlMapper, processLink(uri), httpFetcher, db, urlPatternRuleRepo)

  private def processLink(uri: URI)(link: Link): Option[String] = {
    val url = uri.toString()
    val absoluteLinkUrl = URI.url(uri, link.getUri)
    link match {
      case _ if isAbout(url, absoluteLinkUrl) => Some(absoluteLinkUrl)
      case _ => None
    }
  }

  private def isAbout(baseUrl: String, aboutUrl: String): Boolean = {
    val aboutPages = Seq("about", "aboutus", "about-us", "about_us", "apropos", "a-propos", "a_propos")
    val aboutSuffix = ("/(" + aboutPages.mkString("|") + """)(\.[a-z]{2,4})?/?$""").r
    aboutUrl.startsWith(baseUrl) && aboutSuffix.pattern.matcher(aboutUrl).find
  }
}
