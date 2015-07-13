package com.keepit.rover.fetcher

import com.keepit.rover.article.fetcher.{ FileFetcherFormat, FileHttpFetcherModule }
import com.keepit.rover.model.{ HttpProxy, ProxyScheme }
import com.keepit.rover.test.RoverTestInjector
import org.specs2.matcher.NoConcurrentExecutionContext
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions

import scala.concurrent.Await
import scala.concurrent.duration._

class HttpFetcherTest extends Specification with RoverTestInjector with NoTimeConversions with NoConcurrentExecutionContext {

  withInjector(FileHttpFetcherModule()) { implicit injector =>

    "HttpFetcher" should {

      "pass a given proxy through" in {

        val proxy = Some(HttpProxy(alias = "proxy1", host = "kifi.com", port = 80, scheme = ProxyScheme.Https, username = None, password = None))
        val fetcher = inject[HttpFetcher]
        val fileLocation = FileFetcherFormat("http://kifi.com", "test/com/keepit/rover/document/fixtures/www.cnn.com.health.txt")
        val result = Await.result(fetcher.fetch(FetchRequest(fileLocation, proxy))(identity), 10 seconds)
        result.context.request.proxy === proxy

      }

    }

  }

}
