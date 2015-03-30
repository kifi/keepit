package com.keepit.scraper.extractor

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.net.URI
import com.keepit.model.HttpProxy
import com.keepit.rover.document.tika.HtmlMappers
import com.keepit.rover.document.utils.LinkClassifier
import com.keepit.rover.fetcher.FetchRequest
import com.keepit.scraper.ScraperConfig
import com.keepit.scraper.fetcher.DeprecatedHttpFetcher
import com.keepit.shoebox.ShoeboxScraperClient
import org.apache.tika.parser.html.HtmlMapper
import org.apache.tika.sax.{ Link, LinkContentHandler, TeeContentHandler }
import org.xml.sax.ContentHandler

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class LinkProcessingExtractor(
  url: URI,
  maxContentChars: Int,
  htmlMapper: Option[HtmlMapper],
  processLink: Link => Option[String],
  httpFetcher: DeprecatedHttpFetcher,
  shoeboxScraperClient: ShoeboxScraperClient)
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
        val extractor = new DefaultExtractor(URI.parse(linkUrl).get, maxContentChars, htmlMapper)
        val proxy = syncGetProxyP(url)
        httpFetcher.fetch(FetchRequest(linkUrl, proxy = proxy))(extractor.process)
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

  private[extractor] def getProxyP(url: URI): Future[Option[HttpProxy]] = shoeboxScraperClient.getProxyP(url.toString())
  private[extractor] def syncGetProxyP(url: URI): Option[HttpProxy] = Await.result(getProxyP(url), 10 seconds)
}

@Singleton
class LinkProcessingExtractorProvider @Inject() (httpFetcher: DeprecatedHttpFetcher, shoeboxScraperClient: ShoeboxScraperClient) extends ExtractorProvider {
  def isDefinedAt(uri: URI) = true
  def apply(uri: URI) = new LinkProcessingExtractor(uri, ScraperConfig.maxContentChars, HtmlMappers.default, processLink(uri), httpFetcher, shoeboxScraperClient) // TODO

  private def processLink(uri: URI)(link: Link): Option[String] = {
    val url = uri.toString()
    URI.absoluteUrl(uri, link.getUri).collect { case absoluteLinkUrl if LinkClassifier.isAbout(url, link.getText, absoluteLinkUrl) => absoluteLinkUrl }
  }
}
