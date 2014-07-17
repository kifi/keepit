package com.keepit.scraper.extractor

import com.keepit.scraper.fetcher.HttpFetcher
import org.apache.tika.parser.html.HtmlMapper
import com.keepit.scraper.ScraperConfig
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.net.URI
import org.apache.tika.sax.{ Link, LinkContentHandler, TeeContentHandler }
import org.xml.sax.ContentHandler
import scala.collection.JavaConversions._
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import com.keepit.model.HttpProxy

class LinkProcessingExtractor(
  url: String,
  maxContentChars: Int,
  htmlMapper: Option[HtmlMapper],
  processLink: Link => Option[String],
  httpFetcher: HttpFetcher,
  shoeboxServiceClient: ShoeboxServiceClient)
    extends DefaultExtractor(url, maxContentChars, htmlMapper) {

  private val linkHandler = new LinkContentHandler()
  private val handler = new TeeContentHandler(super.getContentHandler, linkHandler)
  override def getContentHandler: ContentHandler = handler
  def getLinks(): Seq[Link] = linkHandler.getLinks

  private lazy val (linkedContent, linkedKeywords): (Seq[String], Seq[Option[String]]) = {
    val relevantLinks = for {
      link <- getLinks()
      linkUrl <- processLink(link)
    } yield linkUrl
    val linkedContentWithKeywords = relevantLinks.distinct.map {
      case linkUrl =>
        val ts = System.currentTimeMillis
        log.info(s"Scraping additional content from link ${linkUrl} for url ${url}")
        val extractor = new DefaultExtractor(linkUrl, maxContentChars, htmlMapper)
        val proxy = syncGetProxyP(url)
        httpFetcher.fetch(linkUrl, proxy = proxy)(extractor.process)
        val content = extractor.getContent
        val keywords = extractor.getKeywords
        log.info(s"Scraped additional content from link ${linkUrl} for url ${url}: time-lapsed:${System.currentTimeMillis - ts} content.len=${content.length} keywords=$keywords")
        (content, keywords)
    }
    linkedContentWithKeywords.unzip
  }

  override def getContent(): String = (super.getContent() +: linkedContent).mkString(" ● ● ● ").take(maxContentChars)
  override def getKeywords(): Option[String] = {
    val keywords = (super.getKeywords() +: linkedKeywords).flatten.mkString(" ● ● ● ")
    if (keywords.isEmpty) None else Some(keywords)
  }

  private[extractor] def getProxyP(url: String): Future[Option[HttpProxy]] = shoeboxServiceClient.getProxyP(url)
  private[extractor] def syncGetProxyP(url: String): Option[HttpProxy] = Await.result(getProxyP(url), 10 seconds)
}

@Singleton
class LinkProcessingExtractorProvider @Inject() (httpFetcher: HttpFetcher, shoeboxServiceClient: ShoeboxServiceClient) extends ExtractorProvider {
  def isDefinedAt(uri: URI) = true
  def apply(uri: URI) = new LinkProcessingExtractor(uri.toString, ScraperConfig.maxContentChars, DefaultExtractorProvider.htmlMapper, processLink(uri), httpFetcher, shoeboxServiceClient) // TODO

  private def processLink(uri: URI)(link: Link): Option[String] = {
    val url = uri.toString()
    URI.absoluteUrl(uri, link.getUri).collect { case absoluteLinkUrl if isAbout(url, link.getText, absoluteLinkUrl) => absoluteLinkUrl }
  }

  private def isAbout(baseUrl: String, linkText: String, linkUrl: String): Boolean = {
    val aboutText = Set("about", "about us", "a propos")
    val concatenators = Set("", "-", "_")
    val aboutUrls = for { text <- aboutText; c <- concatenators } yield text.mkString(c)
    val aboutUrlRegex = ("/(" + aboutUrls.mkString("|") + """)(\.[a-z]{2,4})?/?$""").r
    linkUrl.startsWith(baseUrl) && (aboutText.contains(linkText.toLowerCase) || aboutUrlRegex.pattern.matcher(linkUrl).find)
  }
}
