package com.keepit.search.feed

import org.specs2.mutable._
import com.keepit.common.db.Id
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.test.Helpers._
import com.keepit.inject._
import com.keepit.test._
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIStates._
import akka.actor.ActorSystem
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.util.PlayAppConfigurationModule

class FeedMetaInfoProviderTest extends Specification with SearchTestInjector {
  "FeedMetaInfoProvider" should {
    "work" in {
      implicit val system = ActorSystem("test")
      withInjector(FakeShoeboxServiceModule(), FakeActorSystemModule(), FakeHttpClientModule(), PlayAppConfigurationModule()) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val uris = client.saveURIs(
          NormalizedURI.withHash(title = Some("1"), normalizedUrl = "http://www.keepit.com/login", state = UNSCRAPABLE),
          NormalizedURI.withHash(title = Some("2"), normalizedUrl = "http://www.keepit.com/help", state = SCRAPED),
          NormalizedURI.withHash(title = Some("3"), normalizedUrl = "http://www.keepit.org/isSensitive", state = SCRAPED)
        )

        val provider = new FeedMetaInfoProvider(client)
        provider.getFeedMetaInfo(uris(0).id.get) === FeedMetaInfo(uris(0), isSensitive = false)
        provider.getFeedMetaInfo(uris(1).id.get) === FeedMetaInfo(uris(1), isSensitive = false)
        provider.getFeedMetaInfo(uris(2).id.get) === FeedMetaInfo(uris(2), isSensitive = true)
      }
    }
  }
}
