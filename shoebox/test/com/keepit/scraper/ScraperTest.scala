package com.keepit.scraper

import com.keepit.search.Article
import play.api.Play.current
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
import com.keepit.test.EmptyApplication
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import org.apache.http.HttpStatus
import scala.collection.mutable.{Map => MutableMap}
import com.keepit.common.db.slick.DBConnection

@RunWith(classOf[JUnitRunner])
class ScraperTest extends SpecificationWithJUnit {
  implicit val config = ScraperConfig()

  "Scraper" should {
    "get a article from an existing website" in {
      val store = new FakeArticleStore()
      val scraper = getMockScraper(store)
      val url = "http://www.keepit.com/existing"
      val uri = NormalizedURIFactory(title = "title", url = url, state = NormalizedURIStates.ACTIVE).copy(id = Some(Id(33)))
      val result = scraper.fetchArticle(uri)

      result.isLeft === true // Left is Article
      result.left.get.title === "foo"
      result.left.get.content === "bar"
    }

    "throw an error from a non-existing website" in {
      val store = new FakeArticleStore()
      val scraper = getMockScraper(store)
      val url = "http://www.keepit.com/missing"
      val uri = NormalizedURIFactory(title = "title", url = url, state = NormalizedURIStates.ACTIVE).copy(id = Some(Id(44)))
      val result = scraper.fetchArticle(uri)
      result.isRight === true // Right is ScraperError
      result.right.get.httpStatusCode === HttpStatus.SC_NOT_FOUND
    }

    "fetch allActive" in {
      running(new EmptyApplication()) {
        inject[DBConnection].readWrite { implicit s =>
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
      running(new EmptyApplication()) {
        val uriRepo = inject[NormalizedURIRepo]
        val scrapeRepo = inject[ScrapeInfoRepo]
        val (uri1, uri2, info1, info2) = inject[DBConnection].readWrite { implicit s =>
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
        val (uri11, uri22) = inject[DBConnection].readOnly { implicit s =>
          (uriRepo.get(uri1.id.get), uriRepo.get(uri2.id.get))
        }

        uri11.state === NormalizedURIStates.SCRAPED
        uri22.state === NormalizedURIStates.SCRAPE_FAILED
      }
    }

    "adjust scrape schedule" in {
      // DEV should be using the default ScraperConfig
      running(new EmptyApplication()) {
        val uriRepo = inject[NormalizedURIRepo]
        val scrapeRepo = inject[ScrapeInfoRepo]
        var (uri1, uri2, info1, info2) = inject[DBConnection].readWrite { implicit s =>
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
        val (info1a, info2a) = inject[DBConnection].readOnly { implicit s =>
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

    "update scrape schedule upon state change" in {
      running(new EmptyApplication()) {
        val uriRepo = inject[NormalizedURIRepo]
        val scrapeRepo = inject[ScrapeInfoRepo]
        var info = inject[DBConnection].readWrite { implicit s =>
          val uri = uriRepo.save(NormalizedURIFactory(title = "existing", url = "http://www.keepit.com/existing"))
          scrapeRepo.getByUri(uri.id.get).get
        }
        inject[DBConnection].readWrite { implicit s =>
          info = scrapeRepo.save(info.withState(ScrapeInfoStates.INACTIVE))
          info.nextScrape === END_OF_TIME
        }
        inject[DBConnection].readWrite { implicit s =>
          info = scrapeRepo.save(info.withState(ScrapeInfoStates.ACTIVE))
          (info.nextScrape.getMillis <= currentDateTime.getMillis) === true
        }
      }
    }
  }

  private[this] def scrapeAndUpdateScrapeInfo(info: ScrapeInfo, scraper: Scraper): ScrapeInfo = {
    val repo = inject[ScrapeInfoRepo]
    inject[DBConnection].readWrite { implicit s => repo.save(info.withNextScrape(info.lastScrape)) }
    scraper.run
    inject[DBConnection].readOnly { implicit s => repo.getByUri(info.uriId).get }
  }

  def getMockScraper(articleStore: ArticleStore, suffix: String = "") = {
  	new Scraper(articleStore, ScraperConfig()) {
  	  override def fetchArticle(uri: NormalizedURI): Either[Article, ScraperError]	 = {
  	    uri.url match {
  	      case "http://www.keepit.com/existing" => Left(Article(
  	          id = uri.id.get,
  	          title = "foo" + suffix,
  	          content = "bar" + suffix,
  	          scrapedAt = currentDateTime,
  	          httpContentType = Option("text/html"),
  	          httpOriginalContentCharset = Option("UTF-8"),
  	          state = SCRAPED,
  	          message = None,
  	          titleLang = Some(Lang("en")),
  	          contentLang = Some(Lang("en"))))
  	      case "http://www.keepit.com/missing" => Right(ScraperError(uri, HttpStatus.SC_NOT_FOUND, "not found"))
  	    }
  	  }
  	}
  }
}
