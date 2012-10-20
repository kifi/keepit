package com.keepit.scraper

import com.keepit.search.Article
import com.keepit.common.db.{CX, Id, State}
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.model.NormalizedURI.States._
import com.keepit.test.EmptyApplication
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import edu.uci.ics.crawler4j.crawler.CrawlConfig
import edu.uci.ics.crawler4j.fetcher.{PageFetcher, PageFetchResult}
import edu.uci.ics.crawler4j.url.WebURL
import org.apache.http.HttpStatus
import scala.collection.mutable.{Map => MutableMap}
import com.keepit.search.ArticleStore

@RunWith(classOf[JUnitRunner])
class ScraperTest extends SpecificationWithJUnit {

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
        var (uri1, uri2) = CX.withConnection { implicit c =>
          val user1 = User(firstName = "Joe", lastName = "Smith").save
          val user2 = User(firstName = "Moo", lastName = "Brown").save
          (NormalizedURI(title = "existing", url = "http://www.keepit.com/existing").save, 
           NormalizedURI(title = "missing", url = "http://www.keepit.com/missing").save)
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
  }

  def getMockScraper(articleStore: ArticleStore) = {
  	new Scraper(articleStore) {
  	  override def fetchArticle(uri: NormalizedURI): Either[Article, ScraperError]	 = {
  	    uri.url match {
  	      case "http://www.keepit.com/existing" => Left(Article(
  	          id = uri.id.get,
  	          title = "foo",
  	          content = "bar",
  	          scrapedAt = currentDateTime,
  	          httpContentType = Option("text/html"),
  	          state = SCRAPED,
  	          message = None))
  	      case "http://www.keepit.com/missing" => Right(ScraperError(uri, HttpStatus.SC_NOT_FOUND, "not found"))
  	    }
  	  } 
  	}
  }
}
