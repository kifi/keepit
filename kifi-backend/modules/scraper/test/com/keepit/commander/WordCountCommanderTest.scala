package com.keepit.commander

import org.specs2.mutable.Specification
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.controller.FakeActionAuthenticatorModule
import com.keepit.common.db.Id
import com.keepit.common.time.{DEFAULT_DATE_TIME_ZONE, currentDateTime}
import com.keepit.inject.ApplicationInjector
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.scraper.{TestScraperServiceClientModule, ScraperServiceClient}
import com.keepit.search.{Article, ArticleStore, Lang}
import akka.actor.ActorSystem
import play.api.test.Helpers.running
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.scraper.FixedResultScraperModule
import com.keepit.scraper.TestScraperServiceModule
import com.keepit.scraper.extractor._
import scala.concurrent.Future
import com.keepit.scraper.BasicArticle
import com.keepit.commanders.WordCountCommanderImpl
import com.keepit.scraper.ScrapeProcessor
import com.keepit.test.TestApplication
import com.keepit.scraper.Signature


class WordCountCommanderTest extends Specification with ApplicationInjector{

  val english = Lang("en")

  def mkArticle(normalizedUriId: Id[NormalizedURI], title: String, content: String) = {
    Article(
      id = normalizedUriId,
      title = title,
      description = None,
      canonicalUrl = None,
      alternateUrls = Set.empty,
      keywords = None,
      media = None,
      content = content,
      scrapedAt = currentDateTime,
      httpContentType = Some("text/html"),
      httpOriginalContentCharset = Option("UTF-8"),
      state = SCRAPED,
      message = None,
      titleLang = Some(english),
      contentLang = Some(english))
  }

  val fakeScrapeProcessor = new ScrapeProcessor {
    def fetchBasicArticle(url:String, proxyOpt:Option[HttpProxy], extractorProviderTypeOpt:Option[ExtractorProviderType]):Future[Option[BasicArticle]] = {
      val content = if (url == "http://twoWords.com") "two words" else "na"
      Future.successful(Some(BasicArticle(title = "not important", content = content, signature = Signature("fixedSignature"), destinationUrl = url)))
    }
    def asyncScrape(uri:NormalizedURI, info:ScrapeInfo, pageInfo:Option[PageInfo], proxyOpt:Option[HttpProxy]):Unit = {}
  }

  "WordCountCommander" should {
    "get word count" in {
      running(new TestApplication(TestScraperServiceModule())){
        val store = inject[ArticleStore]
        val countCache = inject[NormalizedURIWordCountCache]
        val uids = (1 to 3).map{ i => Id[NormalizedURI](i)}
        val a1 = mkArticle(uids(0), title = "", content = "1 2 3 4 5")
        store.+=(uids(0), a1)

        val wcCommander = new WordCountCommanderImpl(store, countCache, fakeScrapeProcessor)
        Await.result(wcCommander.getWordCount(uids(0), url = ""), Duration(1, SECONDS)) === 5

        // delete article, then get word count from cache
        store.-=(uids(0))
        Await.result(wcCommander.getWordCount(uids(0), url = ""), Duration(1, SECONDS)) === 5

        // get from scraper
        Await.result(wcCommander.getWordCount(uids(1), url = "http://twoWords.com"), Duration(1, SECONDS)) === 2
        Await.result(wcCommander.getWordCount(uids(2), url = "http://singleWord.com"), Duration(1, SECONDS)) === 1

        // from cache
        Await.result(wcCommander.getWordCount(uids(1), url = "http://singleWord.com"), Duration(1, SECONDS)) === 2
      }
    }
  }
}
