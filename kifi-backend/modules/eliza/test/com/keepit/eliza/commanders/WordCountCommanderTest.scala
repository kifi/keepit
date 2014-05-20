package com.keepit.eliza.commanders

import org.specs2.mutable.Specification
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.controller.FakeActionAuthenticatorModule
import com.keepit.common.db.Id
import com.keepit.common.time.{DEFAULT_DATE_TIME_ZONE, currentDateTime}
import com.keepit.inject.ApplicationInjector
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIStates.SCRAPED
import com.keepit.model.NormalizedURIWordCountCache
import com.keepit.scraper.{TestScraperServiceClientModule, ScraperServiceClient}
import com.keepit.search.{Article, ArticleStore, Lang}
import com.keepit.test.ElizaApplication
import akka.actor.ActorSystem
import play.api.test.Helpers.running
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.scraper.FixedResultScraperModule



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

  "WordCountCommander" should {
    "get word count" in {
      running(new ElizaApplication(FixedResultScraperModule())){
        val store = inject[ArticleStore]
        val uids = (1 to 3).map{ i => Id[NormalizedURI](i)}
        val a1 = mkArticle(uids(0), title = "", content = "1 2 3 4 5")
        store.+=(uids(0), a1)

        val wcCommander = inject[WordCountCommander]
        Await.result(wcCommander.getWordCount(uids(0), url = ""), Duration(1, SECONDS)) === 5

        // delete article, then get word count from cache
        store.-=(uids(0))
        Await.result(wcCommander.getWordCount(uids(0), url = ""), Duration(1, SECONDS)) === 5

        // get from scraper
        Await.result(wcCommander.getWordCount(uids(1), url = "http://fixedResult.com"), Duration(1, SECONDS)) === 2
        Await.result(wcCommander.getWordCount(uids(2), url = "http://singleWord.com"), Duration(1, SECONDS)) === 1

        // from cache
        Await.result(wcCommander.getWordCount(uids(1), url = "http://singleWord.com"), Duration(1, SECONDS)) === 2
      }
    }
  }
}
