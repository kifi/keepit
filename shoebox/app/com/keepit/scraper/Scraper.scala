package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.search.{Article, ArticleStore}
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._
import com.keepit.common.db.CX
import edu.uci.ics.crawler4j.crawler.{CrawlConfig, Page }
import edu.uci.ics.crawler4j.fetcher.{CustomFetchStatus, PageFetcher, PageFetchResult}
import edu.uci.ics.crawler4j.parser.{HtmlParseData, Parser => C4JParser}
import edu.uci.ics.crawler4j.url.WebURL
import org.apache.http.HttpStatus
import org.apache.tika.detect.DefaultDetector
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.html.BoilerpipeContentHandler
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.Parser
import org.apache.tika.sax.ContentHandlerDecorator
import org.apache.tika.sax.WriteOutContentHandler
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler
import org.xml.sax.SAXException
import com.google.inject.Inject
import play.api.Play.current
import org.joda.time.Seconds

object Scraper {
  val BATCH_SIZE = 10
}

class Scraper @Inject() (articleStore: ArticleStore) extends Logging {
  
  val httpFetcher = new HttpFetcher
  val maxContentChars = 100000 // 100K chars
  
  val config = {
      val conf = new CrawlConfig()
      conf.setIncludeHttpsPages(true)
      conf
  }
  val pageFetcher = new PageFetcher(config)
  val parser = new C4JParser(config);
  
  def run(): Seq[(NormalizedURI, Option[Article])] = {
    val startedTime = currentDateTime
    log.info("starting a new scrape round")
    val uris = CX.withConnection { implicit c =>
      NormalizedURI.getByState(ACTIVE, Scraper.BATCH_SIZE)
    }
    log.info("got %s uris to scrape".format(uris.length))
    val scrapedArticles = processURIs(uris)
    val jobTime = Seconds.secondsBetween(startedTime, currentDateTime).getSeconds()
    log.info("succesfuly scraped %s articles out of %s in %s seconds:\n%s".format(
        scrapedArticles.size, uris.size, jobTime, scrapedArticles map {a => a._1} mkString "\n"))
    scrapedArticles
  }
  
  def processURIs(uris: Seq[NormalizedURI]): Seq[(NormalizedURI, Option[Article])] = uris map safeProcessURI
  
  def safeProcessURI(uri: NormalizedURI): (NormalizedURI, Option[Article]) = try {
      processURI(uri)
    } catch {
      case e => 
        log.error("uncaught exception while scraping uri %s".format(uri), e)
        val errorURI = CX.withConnection { implicit c =>
          uri.withState(NormalizedURI.States.SCRAPE_FAILED).save
        }
        (errorURI, None)
    }
  
  
  private def processURI(uri: NormalizedURI): (NormalizedURI, Option[Article]) = {
    log.info("scraping %s".format(uri))
    fetchArticle(uri) match {
      case Left(article) =>
        // store a scraped article in a store map
        articleStore += (uri.id.get -> article)
        // succeeded. update the state to SCRAPED and save
        val scrapedURI = CX.withConnection { implicit c =>
          uri.withState(NormalizedURI.States.SCRAPED).save
        }
        log.info("fetched uri %s => %s".format(uri, article))
        (scrapedURI, Some(article))
      case Right(error) =>
        // store a fallback article in a store map
        val article = Article(
            id = uri.id.get,
            title = uri.title,
            content = "",
            scrapedAt = currentDateTime,
            httpContentType = None,
            httpOriginalContentCharset = None,
            NormalizedURI.States.SCRAPE_FAILED,
            Option(error.msg))
        articleStore += (uri.id.get -> article)
        // succeeded. update the state to SCRAPE_FAILED and save
        val errorURI = CX.withConnection { implicit c =>
          uri.withState(NormalizedURI.States.SCRAPE_FAILED).save
        }
        (errorURI, None)
    }
  }
  
  private def getContentHandler(url: String, output: ContentHandler): ContentHandler = {
    new BoilerSafeContentHandler(new BoilerpipeContentHandler(output))
  }
  
  def fetchArticle(normalizedUri: NormalizedURI): Either[Article, ScraperError] = {
    fetchArticle(normalizedUri, httpFetcher)
  }
  
  private def fetchArticle(normalizedUri: NormalizedURI, httpFetcher: HttpFetcher): Either[Article, ScraperError] = {
    var url = normalizedUri.url
    val output = new WriteOutContentHandler(maxContentChars)
    val contentHandler = getContentHandler(url, output)
    val metadata = new Metadata()
    
    val context = new ParseContext();
    val detector = new DefaultDetector();
    val parser = new AutoDetectParser(detector);
    context.set(classOf[Parser], parser);
    
    try {
      val fetchStatus = httpFetcher.fetch(url){ input =>
        try {
          parser.parse(input, contentHandler, metadata, context)
        } catch {
          case e =>
            // check if we hit our content size limit (maxContentChars)
            if (output.isWriteLimitReached(e))
              log.warn("max number of characters reached: " + url)
            else
              throw e
        }
      }
      
      fetchStatus.statusCode match {
        case HttpStatus.SC_OK =>
          val title = Option(metadata.get("title")).getOrElse("")
          val content = output.toString
          Left(Article(id = normalizedUri.id.get,
                       title = title,
                       content = content,
                       scrapedAt = currentDateTime,
                       httpContentType = Option(metadata.get("Content-Type")),
                       httpOriginalContentCharset = Option(metadata.get("Content-Encoding")),
                       state = SCRAPED,
                       message = None))
        case _ =>
          Right(ScraperError(normalizedUri, fetchStatus.statusCode, fetchStatus.message.getOrElse("fetch failed")))
      }
    } catch {
      case e => Right(ScraperError(normalizedUri, -1, "fetch failed: %s".format(e.toString)))
    }
  }
  
  private def fetchArticle(normalizedUri: NormalizedURI, pageFetcher: PageFetcher): Either[Article, ScraperError] = {
    var fetchResult: Option[PageFetchResult] = None
    val webURL = new WebURL
    var scrapeUrl = normalizedUri.url
    val domainStartIdx = scrapeUrl.indexOf("//") + 2
    if (scrapeUrl.indexOf('/', domainStartIdx) < 0) {
      scrapeUrl = scrapeUrl + '/'
    }
    try {
      webURL.setURL(scrapeUrl)
    } catch {
      case e => throw new Exception("could not set url for scrape url %s".format(scrapeUrl), e)
    }
    try {
      val result = pageFetcher.fetchHeader(webURL)
      fetchResult = Some(result)
      val statusCode = result.getStatusCode()
      
      if (statusCode == HttpStatus.SC_OK) {
      	// the status is OK. now scrape it.
        val page = new Page(webURL)
        if (result.fetchContent(page) && parser.parse(page, normalizedUri.url)) {
          page.getParseData() match {
            case htmlData: HtmlParseData =>
              log.info("page of url %s parsed with charset %s and title %s".format(
                  normalizedUri, page.getContentCharset(), htmlData.getTitle()))
              Left(Article(
                  id = normalizedUri.id.get,
                  title = Option(htmlData.getTitle()).getOrElse(""),
                  content = Option(htmlData.getText()).getOrElse(""),
                  scrapedAt = currentDateTime,
                  httpContentType = Option(page.getContentType()),
                  httpOriginalContentCharset = Option(page.getContentCharset()),
                  state = SCRAPED,
                  message = None))
            case _ => Right(ScraperError(normalizedUri, statusCode, "not html"))
          }
      	} else {
          Right(ScraperError(normalizedUri, statusCode, "parse failed"))
        }
      } else if (statusCode == HttpStatus.SC_MOVED_PERMANENTLY || statusCode == HttpStatus.SC_MOVED_TEMPORARILY) {
        // TODO: redirect?
        // val movedToUrl = result.getMovedToUrl();
        Right(ScraperError(normalizedUri, statusCode, "fetch failed: httpStatusCode=%d description=%s".format(statusCode, CustomFetchStatus.getStatusDescription(statusCode))))
      } else if (result.getStatusCode() == CustomFetchStatus.PageTooBig) {
        Right(ScraperError(normalizedUri, statusCode, "fetch failed: httpStatusCode=%d description=%s [%s]".format(statusCode, CustomFetchStatus.getStatusDescription(statusCode), "revisit crawler4j config")))
      } else {
        Right(ScraperError(normalizedUri, statusCode, "fetch failed: httpStatusCode=%d description=%s".format(statusCode, CustomFetchStatus.getStatusDescription(statusCode))))
      }
    } finally {
      fetchResult.foreach(_.discardContentIfNotConsumed())
    }
  }
  
  def close() {
    httpFetcher.close()
  }
}


// To prevent Boilerpipe blowing up we need this
class BoilerSafeContentHandler(handler: ContentHandler) extends ContentHandlerDecorator(handler) {
  
  var inAnchor = false
  
  override def startElement(uri: String, localName: String, qName: String, atts: Attributes) {
    localName.toLowerCase() match {
      case "a" => //nested anchor tags blow up Boilerpipe. so we close it if one is already open
        if (inAnchor) endElement(uri, localName, qName)
        super.startElement(uri, localName, qName, atts)
        inAnchor = true
      case _ => super.startElement(uri, localName, qName, atts)
    }
  }
  
  override def endElement(uri: String, localName: String, qName: String) {
    localName.toLowerCase() match {
      case "a" => if (inAnchor) {
        super.endElement(uri, localName, qName)
        inAnchor = false
      }
      case _ => super.endElement(uri, localName, qName)
    }
  }
}
