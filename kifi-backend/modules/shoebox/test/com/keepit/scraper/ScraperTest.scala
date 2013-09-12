package com.keepit.scraper

import com.google.inject.Injector
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.model._
import com.keepit.search.ArticleStore
import com.keepit.test.ShoeboxTestInjector
import com.keepit.scraper.extractor.Extractor
import com.keepit.scraper.extractor.TikaBasedExtractor
import org.specs2.mutable._
import org.apache.http.HttpStatus
import org.apache.http.protocol.BasicHttpContext
import org.apache.tika.sax.BodyContentHandler
import org.joda.time.DateTime
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.OutputStreamWriter
import com.keepit.common.store.FakeS3ScreenshotStore

class ScraperTest extends Specification with ShoeboxTestInjector {
  val config = ScraperConfig(
    minInterval = 18.0d, //hours
    maxInterval = 30.0d, //hours
    intervalIncrement = 4.0d, //hours
    intervalDecrement = 2.0d, //hours
    initialBackoff = 1.0d, //hours
    maxBackoff = 12.0d, //hours
    changeThreshold = 0.05
  )

  "Scraper" should {
    "get a article from an existing website" in {
      withDb() { implicit injector =>
        val store = new FakeArticleStore()
        val scraper = getMockScraper(store)
        val url = "http://www.keepit.com/existing"
        val uri = NormalizedURI.withHash(title = Some("title"), normalizedUrl = url, state = NormalizedURIStates.SCRAPE_WANTED).copy(id = Some(Id(33)))
        val result = scraper.fetchArticle(uri, info = ScrapeInfo(uriId = uri.id.get))

        result must beAnInstanceOf[Scraped] // Article
        (result: @unchecked) match {
          case Scraped(article, signature, redirects) =>
            article.title === "foo"
            article.content === "this is a body text. bar."
        }
      }
    }

    "throw an error from a non-existing website" in {
      withDb() { implicit injector =>
        val store = new FakeArticleStore()
        val scraper = getMockScraper(store)
        val url = "http://www.keepit.com/missing"
        val uri = NormalizedURI.withHash(title = Some("title"), normalizedUrl = url, state = NormalizedURIStates.SCRAPE_WANTED).copy(id = Some(Id(44)))
        val result = scraper.fetchArticle(uri, info = ScrapeInfo(uriId = uri.id.get))
        result must beAnInstanceOf[Error]
        (result: @unchecked) match {
          case Error(httpStatus, _) => httpStatus === HttpStatus.SC_NOT_FOUND
        }
      }
    }

    "fetch allActive" in {
      withDb() { implicit injector =>
        inject[Database].readWrite { implicit s =>
          val uriRepo = inject[NormalizedURIRepo]
          val scrapeRepo = inject[ScrapeInfoRepo]
          val uri1 = uriRepo.save(NormalizedURI.withHash(title = Some("existing"), normalizedUrl = "http://www.keepit.com/existing")
              .withState(NormalizedURIStates.SCRAPED))
          val uri2 = uriRepo.save(NormalizedURI.withHash(title = Some("missing"), normalizedUrl = "http://www.keepit.com/missing")
              .withState(NormalizedURIStates.SCRAPED))
          val info1 = scrapeRepo.getByUri(uri1.id.get).get
          val info2 = scrapeRepo.getByUri(uri2.id.get).get
          val all = scrapeRepo.allActive
          all.size === 2
        }
      }
    }

    "fetch SCRAPE_WANTED uris and scrape them" in {
      withDb() { implicit injector =>
        val uriRepo = inject[NormalizedURIRepo]
        val scrapeRepo = inject[ScrapeInfoRepo]
        val (uri1, uri2, info1, info2) = inject[Database].readWrite { implicit s =>
          val uri1 = uriRepo.save(NormalizedURI.withHash(title = Some("existing"), normalizedUrl = "http://www.keepit.com/existing", state = NormalizedURIStates.SCRAPE_WANTED))
          val uri2 = uriRepo.save(NormalizedURI.withHash(title = Some("missing"), normalizedUrl = "http://www.keepit.com/missing", state = NormalizedURIStates.SCRAPE_WANTED))
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
      withDb() { implicit injector =>
        val uriRepo = inject[NormalizedURIRepo]
        val scrapeRepo = inject[ScrapeInfoRepo]
        var (uri1, uri2, info1, info2) = inject[Database].readWrite { implicit s =>
          val uri1 = uriRepo.save(NormalizedURI.withHash(title = Some("existing"), normalizedUrl = "http://www.keepit.com/existing", state = NormalizedURIStates.SCRAPE_WANTED))
          val uri2 = uriRepo.save(NormalizedURI.withHash(title = Some("missing"), normalizedUrl = "http://www.keepit.com/missing", state = NormalizedURIStates.SCRAPE_WANTED))
          val info1 = scrapeRepo.getByUri(uri1.id.get).get
          val info2 = scrapeRepo.getByUri(uri2.id.get).get
          (uri1, uri2, info1, info2)
        }
        val store = new FakeArticleStore()
        val httpFetcher = getMockHttpFetcher()
        val scraper = getMockScraper(store, httpFetcher)
        scraper.run
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
        (0 to ((config.maxInterval / config.intervalIncrement).toInt + 2)).foreach{ i =>
          info1 = scrapeAndUpdateScrapeInfo(info1, scraper)
        }
        info1.interval === config.maxInterval

        // min interval
        info1 = info1a
        (0 to ((info1.interval / config.intervalDecrement).toInt + 2)).foreach{ i =>
          httpFetcher.setSuffix(i.toString)
          info1 = scrapeAndUpdateScrapeInfo(info1, scraper)
        }
        info1.interval === config.minInterval

        info2 = info2a
        // exponential backoff
        var backoff = info2.nextScrape.getMillis - currentDateTime.getMillis
        backoff = (0 until 3).foldLeft(backoff){ (backoff, i) =>
          info2 = scrapeAndUpdateScrapeInfo(info2, scraper)
          val newBackoff = info2.nextScrape.getMillis - currentDateTime.getMillis
          (newBackoff > (backoff * 1.5)) === true
          newBackoff
        }
        // max backoff
        var repeat = 0
        while (config.initialBackoff * (1 << repeat) < config.maxBackoff) repeat += 1
        backoff = (3 to repeat).foldLeft(backoff){ (backoff, i) =>
          info2 = scrapeAndUpdateScrapeInfo(info2, scraper)
          info2.nextScrape.getMillis - currentDateTime.getMillis
        }
        val maxBackoffMillis = config.maxBackoff * 60*60*1000
        (backoff > maxBackoffMillis * 9/10 && backoff < maxBackoffMillis * 11/10) === true
      }
    }

    "not scrape a not-modified page" in {
      // DEV should be using the default ScraperConfig
      withDb() { implicit injector =>
        val uriRepo = inject[NormalizedURIRepo]
        val scrapeRepo = inject[ScrapeInfoRepo]
        val (uri1, info1) = inject[Database].readWrite { implicit s =>
          val uri1 = uriRepo.save(NormalizedURI.withHash(title = Some("notModified"), normalizedUrl = "http://www.keepit.com/notModified", state = NormalizedURIStates.SCRAPE_WANTED))
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
      withDb() { implicit injector =>
        val uriRepo = inject[NormalizedURIRepo]
        val scrapeRepo = inject[ScrapeInfoRepo]
        var info = inject[Database].readWrite { implicit s =>
          val uri = uriRepo.save(NormalizedURI.withHash(title = Some("existing"), normalizedUrl = "http://www.keepit.com/existing", state = NormalizedURIStates.SCRAPE_WANTED))
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

  private[this] def scrapeAndUpdateScrapeInfo(info: ScrapeInfo, scraper: Scraper)(implicit injector: Injector): ScrapeInfo = {
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

  def getMockHttpFetcher() = {
    new HttpFetcher with SettableSuffix {
      def fetch(url: String , ifModifiedSince: Option[DateTime] = None)(f: HttpInputStream => Unit): HttpFetchStatus = {
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
  }

  def getMockScraper(articleStore: ArticleStore, mockHttpFetcher: HttpFetcher = getMockHttpFetcher)(implicit injector: Injector) = {
    new Scraper(inject[Database], mockHttpFetcher, articleStore, config,
      inject[ScrapeInfoRepo], inject[NormalizedURIRepo], inject[HealthcheckPlugin],
      inject[BookmarkRepo], inject[UnscrapableRepo], new FakeS3ScreenshotStore) {
      override protected def getExtractor(url: String): Extractor = {
        new TikaBasedExtractor(url, 10000, None) {
          protected def getContentHandler = new BodyContentHandler(output)
        }
      }
    }
  }

  trait SettableSuffix {
    var suffix = ""
    def setSuffix(s: String) { suffix = s }
  }
}
