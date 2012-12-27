package com.keepit.scraper

import com.keepit.search.Article
import com.keepit.common.db.{CX, Id, State}
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.NormalizedURI.States._
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
import com.keepit.test.EmptyApplication
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import org.apache.http.HttpStatus
import scala.collection.mutable.{Map => MutableMap}

@RunWith(classOf[JUnitRunner])
class ScraperTest extends SpecificationWithJUnit {
  implicit val config = ScraperConfig()

  "Scraper" should {
    "get a article from an existing website" in {
      val store = new FakeArticleStore()
      val scraper = getMockScraper(store)
      val url = "http://www.keepit.com/existing"
      val uri = NormalizedURI(title = "title", url = url, state = NormalizedURI.States.ACTIVE).copy(id = Some(Id(33)))
      val result = scraper.fetchArticle(uri)

      result.isLeft === true // Left is Article
      result.left.get.title === "foo"
      result.left.get.content === "bar"
    }

    "throw an error from a non-existing website" in {
      val store = new FakeArticleStore()
      val scraper = getMockScraper(store)
      val url = "http://www.keepit.com/missing"
      val uri = NormalizedURI(title = "title", url = url, state = NormalizedURI.States.ACTIVE).copy(id = Some(Id(44)))
      val result = scraper.fetchArticle(uri)
      result.isRight === true // Right is ScraperError
      result.right.get.httpStatusCode === HttpStatus.SC_NOT_FOUND
    }

    "fetch ACTIVE uris and scrape them" in {
      running(new EmptyApplication()) {
        var (uri1, uri2, info1, info2) = CX.withConnection { implicit c =>
          val uri1 = NormalizedURI(title = "existing", url = "http://www.keepit.com/existing").save
          val uri2 = NormalizedURI(title = "missing", url = "http://www.keepit.com/missing").save
          val info1 = ScrapeInfo.ofUri(uri1).save
          val info2 = ScrapeInfo.ofUri(uri2).save
          (uri1, uri2, info1, info2)
        }
        val store = new FakeArticleStore()
        val scraper = getMockScraper(store)
        scraper.run
        store.size === 2

        // get URIs from db
        CX.withConnection { implicit c =>
          uri1 = NormalizedURI.get(uri1.id.get)
          uri2 = NormalizedURI.get(uri2.id.get)
        }

        uri1.state === NormalizedURI.States.SCRAPED
        uri2.state === NormalizedURI.States.SCRAPE_FAILED
      }
    }

    "adjust scrape schedule" in {
      // DEV should be using the default ScraperConfig
      running(new EmptyApplication()) {
        var (uri1, uri2, info1, info2) = CX.withConnection { implicit c =>
          val uri1 = NormalizedURI(title = "existing", url = "http://www.keepit.com/existing").save
          val uri2 = NormalizedURI(title = "missing", url = "http://www.keepit.com/missing").save
          val info1 = ScrapeInfo.ofUri(uri1).save
          val info2 = ScrapeInfo.ofUri(uri2).save
          (uri1, uri2, info1, info2)
        }
        val store = new FakeArticleStore()
        getMockScraper(store).run
        store.size === 2

        // get ScrapeInfo from db
        val (info1a, info2a) = CX.withConnection { implicit c => (ScrapeInfo.ofUri(uri1), ScrapeInfo.ofUri(uri2)) }

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
        var info = CX.withConnection { implicit c =>
          val uri = NormalizedURI(title = "existing", url = "http://www.keepit.com/existing").save
          ScrapeInfo.ofUri(uri).save
        }
        CX.withConnection { implicit c =>
          info = info.withState(ScrapeInfo.States.INACTIVE).save
          info.nextScrape === ScrapeInfo.NEVER
        }
        CX.withConnection { implicit c =>
          info = info.withState(ScrapeInfo.States.ACTIVE).save
          (info.nextScrape.getMillis <= currentDateTime.getMillis) === true
        }
      }
    }
  }

  private[this] def scrapeAndUpdateScrapeInfo(info: ScrapeInfo, scraper: Scraper) = {
    CX.withConnection { implicit c => info.withNextScrape(info.lastScrape).save }
    scraper.run
    CX.withConnection { implicit c => ScrapeInfo.ofUriId(info.uriId) }
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
