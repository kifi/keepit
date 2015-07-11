package com.keepit.rover.document

import com.google.inject.Injector
import com.keepit.rover.article.fetcher.{ FileFetcherFormat, FileHttpFetcherModule }
import com.keepit.rover.document.tika.{ MainContentHandler, TikaDocument }
import com.keepit.rover.fetcher.FetchResult
import com.keepit.rover.model.RoverUrlRuleRepo
import com.keepit.rover.test.RoverTestInjector
import org.specs2.matcher.NoConcurrentExecutionContext
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Await }

class TikaDocumentFetcherTest extends Specification with RoverTestInjector with NoTimeConversions with NoConcurrentExecutionContext {

  def fetchTika(url: String, file: String, maxContentChars: Int = MainContentHandler.defaultMaxContentChars)(implicit injector: Injector): FetchResult[TikaDocument] = {
    val documentFetcher = inject[RoverDocumentFetcher]

    val fetchUrl = FileFetcherFormat(url, "test/com/keepit/rover/document/fixtures/" + file)

    Await.result(documentFetcher.fetchTikaDocument(fetchUrl, shouldThrottle = false, maxContentChars = maxContentChars), 10.seconds)
  }

  "RoverDocumentFetcher" should {

    "limit content chars for tika documents" in {

      withDb(FileHttpFetcherModule()) { implicit injector =>

        val result1 = fetchTika("https://cnn.com/url2", "www.cnn.com.health.txt", 500).resolve.get.get
        result1.getContent.get.contains("Ralph Lauren") === false

        val result2 = fetchTika("https://cnn.com/url2", "www.cnn.com.health.txt", 1000).resolve.get.get
        result2.getContent.get.contains("Ralph Lauren") === true

      }

    }
  }

}
