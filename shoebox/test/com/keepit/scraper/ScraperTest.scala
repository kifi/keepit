package com.keepit.scraper

import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
import com.keepit.test.EmptyApplication
import com.keepit.scraper.extractor.Extractor
import com.keepit.scraper.extractor.TikaBasedExtractor
import org.specs2.mutable._
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import org.apache.http.HttpStatus
import org.apache.http.protocol.BasicHttpContext
import org.apache.tika.sax.BodyContentHandler
import org.joda.time.DateTime
import scala.annotation.unchecked
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.OutputStreamWriter

class ScraperTest extends Specification {
  implicit val config = ScraperConfig(
    minInterval = 12.0d, //hours
    maxInterval = 1024.0d, //hours
    intervalIncrement = 2.0d, //hours
    intervalDecrement = 1.0d, //hours
    initialBackoff = 1.0d, //hours
    maxBackoff = 1024.0d, //hours
    changeThreshold = 0.05
  )

  "Scraper" should {
    "get a article from an existing website" in {
      running(new EmptyApplication().withFakeHealthcheck) {
        val store = new FakeArticleStore()
        val scraper = getMockScraper(store)
        val url = "http://www.keepit.com/existing"
        val uri = NormalizedURIFactory(title = "title", url = url, state = NormalizedURIStates.ACTIVE).copy(id = Some(Id(33)))
        val result = scraper.fetchArticle(uri, info = ScrapeInfo(uriId = uri.id.get), false)

        result must beAnInstanceOf[Scraped] // Article
        (result: @unchecked) match {
          case Scraped(article, signature) =>
            article.title === "foo"
            article.content === "this is a body text. bar."
        }
      }
    }

    "throw an error from a non-existing website" in {
      running(new EmptyApplication().withFakeHealthcheck) {
        val store = new FakeArticleStore()
        val scraper = getMockScraper(store)
        val url = "http://www.keepit.com/missing"
        val uri = NormalizedURIFactory(title = "title", url = url, state = NormalizedURIStates.ACTIVE).copy(id = Some(Id(44)))
        val result = scraper.fetchArticle(uri, info = ScrapeInfo(uriId = uri.id.get), false)
        result must beAnInstanceOf[Error]
        (result: @unchecked) match {
          case Error(httpStatus, _) => httpStatus === HttpStatus.SC_NOT_FOUND
        }
      }
    }

    "fetch allActive" in {
      running(new EmptyApplication().withFakeHealthcheck) {
        inject[Database].readWrite { implicit s =>
          val uriRepo = inject[NormalizedURIRepo]
          val scrapeRepo = inject[ScrapeInfoRepo]
          val uri1 = uriRepo.save(NormalizedURIFactory(title = "existing", url = "http://www.keepit.com/existing").withState(NormalizedURIStates.INDEXED))
          val uri2 = uriRepo.save(NormalizedURIFactory(title = "missing", url = "http://www.keepit.com/missing").withState(NormalizedURIStates.INDEXED))
          val info1 = scrapeRepo.getByUri(uri1.id.get).get
          val info2 = scrapeRepo.getByUri(uri2.id.get).get
          val all = scrapeRepo.allActive
          all.size === 2
        }
      }
    }

    "fetch ACTIVE uris and scrape them" in {
      running(new EmptyApplication().withFakeHealthcheck) {
        val uriRepo = inject[NormalizedURIRepo]
        val scrapeRepo = inject[ScrapeInfoRepo]
        val (uri1, uri2, info1, info2) = inject[Database].readWrite { implicit s =>
          val uri1 = uriRepo.save(NormalizedURIFactory(title = "existing", url = "http://www.keepit.com/existing"))
          val uri2 = uriRepo.save(NormalizedURIFactory(title = "missing", url = "http://www.keepit.com/missing"))
          val info1 = scrapeRepo.getByUri(uri1.id.get).get
          val info2 = scrapeRepo.getByUri(uri2.id.get).get
          (uri1, uri2, info1, info2)
        }
        val store = new FakeArticleStore()
        val scraper = getMockScraper(store)
        scraper.run
        store.size === 2

        // get URIs from db
        val (uri11, uri22) = inject[Database].readOnly { implicit s =>
          (uriRepo.get(uri1.id.get), uriRepo.get(uri2.id.get))
        }

        uri11.state === NormalizedURIStates.SCRAPED
        uri22.state === NormalizedURIStates.SCRAPE_FAILED
      }
    }

    "adjust scrape schedule" in {
      // DEV should be using the default ScraperConfig
      running(new EmptyApplication().withFakeHealthcheck) {
        val uriRepo = inject[NormalizedURIRepo]
        val scrapeRepo = inject[ScrapeInfoRepo]
        var (uri1, uri2, info1, info2) = inject[Database].readWrite { implicit s =>
          val uri1 = uriRepo.save(NormalizedURIFactory(title = "existing", url = "http://www.keepit.com/existing"))
          val uri2 = uriRepo.save(NormalizedURIFactory(title = "missing", url = "http://www.keepit.com/missing"))
          val info1 = scrapeRepo.getByUri(uri1.id.get).get
          val info2 = scrapeRepo.getByUri(uri2.id.get).get
          (uri1, uri2, info1, info2)
        }
        val store = new FakeArticleStore()
        getMockScraper(store).run
        store.size === 2

        // get ScrapeInfo from db
        val (info1a, info2a) = inject[Database].readOnly { implicit s =>
          (scrapeRepo.getByUri(uri1.id.get).get, scrapeRepo.getByUri(uri2.id.get).get)
        }

        info1a.failures === 0
        (info1a.interval < info1.interval) === true
        ((info1a.lastScrape compareTo info1.lastScrape) > 0) === true
        ((info1a.nextScrape compareTo info1.nextScrape) > 0) === true
        (info1a.signature.length > 0) === true

        info2a.failures === 1
        (info2a.interval > info2.interval) === true
        ((info2a.lastScrape compareTo info2.lastScrape) == 0) === true
        ((info2a.nextScrape compareTo info2.nextScrape) > 0) === true
        (info2a.signature.length == 0) === true

        // max interval
        info1 = info1a
        (0 to ((config.maxInterval / config.intervalIncrement).toInt * 2)).foreach{ i =>
          info1 = scrapeAndUpdateScrapeInfo(info1, getMockScraper(store))
        }
        info1.interval === config.maxInterval

        // min interval
        info1 = info1a
        (0 to ((info1.interval / config.intervalDecrement).toInt * 2)).foreach{ i =>
          info1 = scrapeAndUpdateScrapeInfo(info1, getMockScraper(store, i.toString))
        }
        info1.interval === config.minInterval

        info2 = info2a
        // exponential backoff
        var backoff = info2.nextScrape.getMillis - currentDateTime.getMillis
        backoff = (0 until 5).foldLeft(backoff){ (backoff, i) =>
          info2 = scrapeAndUpdateScrapeInfo(info2, getMockScraper(store))
          val newBackoff = info2.nextScrape.getMillis - currentDateTime.getMillis
          (newBackoff > (backoff * 1.5)) === true
          newBackoff
        }
        // max backoff
        backoff = (5 until 20).foldLeft(backoff){ (backoff, i) =>
          info2 = scrapeAndUpdateScrapeInfo(info2, getMockScraper(store))
          info2.nextScrape.getMillis - currentDateTime.getMillis
        }
        val maxBackoffMillis = config.maxBackoff * 60*60*1000
        (backoff > maxBackoffMillis * 9/10 && backoff < maxBackoffMillis * 11/10) === true
      }
    }

    "not scrape a not-modified page" in {
      // DEV should be using the default ScraperConfig
      running(new EmptyApplication().withFakeHealthcheck) {
        val uriRepo = inject[NormalizedURIRepo]
        val scrapeRepo = inject[ScrapeInfoRepo]
        var (uri1, info1) = inject[Database].readWrite { implicit s =>
          val uri1 = uriRepo.save(NormalizedURIFactory(title = "notModified", url = "http://www.keepit.com/notModified"))
          val info1 = scrapeRepo.getByUri(uri1.id.get).get
          (uri1, info1)
        }
        val store = new FakeArticleStore()
        val scraper = getMockScraper(store)
        scraper.run
        store.size === 1

        // get ScrapeInfo from db
        val info1a = inject[Database].readOnly { implicit s =>
          scrapeRepo.getByUri(uri1.id.get).get
        }

        info1a.failures === 0
        (info1a.interval < info1.interval) === true
        ((info1a.lastScrape compareTo info1.lastScrape) > 0) === true
        ((info1a.nextScrape compareTo info1.nextScrape) > 0) === true
        (info1a.signature.length > 0) === true

        val repo = inject[ScrapeInfoRepo]
        inject[Database].readWrite { implicit s => repo.save(info1a.withNextScrape(info1a.lastScrape)) }
        scraper.run.head._2 must beNone // check the article

        val info1b = inject[Database].readOnly { implicit s => repo.getByUri(info1a.uriId).get }
        ((info1b.lastScrape compareTo info1a.lastScrape) == 0) === true // last scrape should not be changed
        (info1b.interval > info1a.interval) === true
        ((info1b.nextScrape compareTo info1a.nextScrape) > 0) === true

        inject[Database].readWrite { implicit s => repo.save(info1b.withNextScrape(info1b.lastScrape)) }
        scraper.run.head._2 must beNone // check the article

        val info1c = inject[Database].readOnly { implicit s => repo.getByUri(info1b.uriId).get }
        ((info1c.lastScrape compareTo info1b.lastScrape) == 0) === true // last scrape should not be changed
        (info1c.interval > info1b.interval) === true
        ((info1c.nextScrape compareTo info1b.nextScrape) > 0) === true
      }
    }

    "update scrape schedule upon state change" in {
      running(new EmptyApplication().withFakeHealthcheck) {
        val uriRepo = inject[NormalizedURIRepo]
        val scrapeRepo = inject[ScrapeInfoRepo]
        var info = inject[Database].readWrite { implicit s =>
          val uri = uriRepo.save(NormalizedURIFactory(title = "existing", url = "http://www.keepit.com/existing"))
          scrapeRepo.getByUri(uri.id.get).get
        }
        inject[Database].readWrite { implicit s =>
          info = scrapeRepo.save(info.withState(ScrapeInfoStates.INACTIVE))
          info.nextScrape === END_OF_TIME
        }
        inject[Database].readWrite { implicit s =>
          info = scrapeRepo.save(info.withState(ScrapeInfoStates.ACTIVE))
          (info.nextScrape.getMillis <= currentDateTime.getMillis) === true
        }
      }
    }
  }

  private[this] def scrapeAndUpdateScrapeInfo(info: ScrapeInfo, scraper: Scraper): ScrapeInfo = {
    val repo = inject[ScrapeInfoRepo]
    inject[Database].readWrite { implicit s => repo.save(info.withNextScrape(info.lastScrape)) }
    scraper.run
    inject[Database].readOnly { implicit s => repo.getByUri(info.uriId).get }
  }

  private def toHttpInputStream(text: String) = {
    val baos = new ByteArrayOutputStream
    val writer = new OutputStreamWriter(baos)
    writer.write(text)
    writer.close()
    new HttpInputStream(new ByteArrayInputStream(baos.toByteArray))
  }

  def getMockScraper(articleStore: ArticleStore, suffix: String = "") = {
    val mockHttpFetcher = new HttpFetcher {
      def fetch(url: String , ifModifiedSince: Option[DateTime] = None, useProxy: Boolean = false)(f: HttpInputStream => Unit): HttpFetchStatus = {
        val httpContext = new BasicHttpContext()
        val htmlTemplate = "<html> <head><title>foo%s</title></head> <body>this is a body text. bar%s.</body> </html>"
        url match {
          case "http://www.keepit.com/existing" =>
            val input = toHttpInputStream(htmlTemplate.format(suffix, suffix))
            input.setContentType("text/html")
            f(input)
            HttpFetchStatus(HttpStatus.SC_OK, None, httpContext)
          case "http://www.keepit.com/notModified" =>
            ifModifiedSince match {
              case None =>
                val input = toHttpInputStream(htmlTemplate.format(suffix, suffix))
                input.setContentType("text/html")
                f(input)
                HttpFetchStatus(HttpStatus.SC_OK, None, httpContext)
              case Some(_) =>
                HttpFetchStatus(HttpStatus.SC_NOT_MODIFIED, None, httpContext)
            }
          case _ =>
            HttpFetchStatus(HttpStatus.SC_NOT_FOUND, Some("not found"), httpContext)
        }
      }
      def close() {}
    }
    new Scraper(inject[Database], mockHttpFetcher, articleStore, ScraperConfig(),
      inject[ScrapeInfoRepo], inject[NormalizedURIRepo], inject[HealthcheckPlugin], inject[UnscrapableRepo]) {
      override protected def getExtractor(url: String): Extractor = {
        new TikaBasedExtractor(url, 10000) {
          protected def getContentHandler = new BodyContentHandler(output)
        }
      }
    }
  }
}
