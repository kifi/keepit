package com.keepit.search.feed

import org.specs2.mutable._
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.test.Helpers._
import com.keepit.test._
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.search.sharding.ActiveShards
import com.keepit.search.sharding.ShardSpecParser
import com.keepit.search.{ SearchServiceClient, SearchTestHelper }
import org.joda.time.DateTime
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE
import com.keepit.search.graph.bookmark._
import scala.Some

class FeedCommanderTest extends Specification with SearchApplicationInjector with SearchTestHelper {

  def setup(client: FakeShoeboxServiceClientImpl) = {
    val users = client.saveUsers(
      User(firstName = "u0", lastName = "fake"),
      User(firstName = "u1", lastName = "fake"),
      User(firstName = "u2", lastName = "fake"),
      User(firstName = "u3", lastName = "fake")
    )

    val uris = client.saveURIs(
      NormalizedURI.withHash(title = Some("0"), normalizedUrl = "http://www.keepit.com/login", state = UNSCRAPABLE), // kept by u1
      NormalizedURI.withHash(title = Some("1"), normalizedUrl = "http://www.keepit.com/video", state = SCRAPED), // u1, u2, u3
      NormalizedURI.withHash(title = Some("2"), normalizedUrl = "http://www.keepit.com/faq", state = SCRAPED), // u0, u2
      NormalizedURI.withHash(title = Some("3"), normalizedUrl = "http://www.keepit.com/isSensitive", state = SCRAPED), // u1
      NormalizedURI.withHash(title = Some("4"), normalizedUrl = "http://www.keepit.com/picture", state = SCRAPED), // u2, u3
      NormalizedURI.withHash(title = Some("5"), normalizedUrl = "http://www.keepit.com/private", state = SCRAPED) // private by u2
    )

    val t0 = new DateTime(2014, 2, 17, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

    val bms = client.saveBookmarks(
      Keep(createdAt = t0, uriId = uris(0).id.get, urlId = Id[URL](-1), url = uris(0).url, userId = users(1).id.get, source = KeepSource.keeper, isPrivate = false, libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())),

      Keep(createdAt = t0.plusMinutes(1), uriId = uris(1).id.get, urlId = Id[URL](-1), url = uris(1).url, userId = users(1).id.get, source = KeepSource.keeper, isPrivate = false, libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())),
      Keep(createdAt = t0.plusMinutes(2), uriId = uris(1).id.get, urlId = Id[URL](-1), url = uris(1).url, userId = users(2).id.get, source = KeepSource.keeper, isPrivate = false, libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())),
      Keep(createdAt = t0.plusMinutes(3), uriId = uris(1).id.get, urlId = Id[URL](-1), url = uris(1).url, userId = users(3).id.get, source = KeepSource.keeper, isPrivate = false, libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())),

      Keep(createdAt = t0.plusMinutes(4), uriId = uris(2).id.get, urlId = Id[URL](-1), url = uris(2).url, userId = users(0).id.get, source = KeepSource.keeper, isPrivate = false, libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())),
      Keep(createdAt = t0.plusMinutes(5), uriId = uris(2).id.get, urlId = Id[URL](-1), url = uris(2).url, userId = users(2).id.get, source = KeepSource.keeper, isPrivate = false, libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())),

      Keep(createdAt = t0.plusMinutes(6), uriId = uris(3).id.get, urlId = Id[URL](-1), url = uris(3).url, userId = users(1).id.get, source = KeepSource.keeper, isPrivate = false, libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())),

      Keep(createdAt = t0.plusMinutes(7), uriId = uris(4).id.get, urlId = Id[URL](-1), url = uris(4).url, userId = users(2).id.get, source = KeepSource.keeper, isPrivate = false, libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())),
      Keep(createdAt = t0.plusMinutes(8), uriId = uris(4).id.get, urlId = Id[URL](-1), url = uris(4).url, userId = users(3).id.get, source = KeepSource.keeper, isPrivate = false, libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]())),

      Keep(createdAt = t0.plusMinutes(9), uriId = uris(5).id.get, urlId = Id[URL](-1), url = uris(5).url, userId = users(2).id.get, source = KeepSource.keeper, isPrivate = true, libraryId = Some(Id[Library](1)), libraryExternalId = Some(ExternalId[Library]()))
    )

    val connections = client.saveConnections(Map(users(0).id.get -> Set(users(1).id.get, users(2).id.get)))
    (users, uris, bms, t0)
  }

  "FeedCommander" should {
    "work" in {
      running(application) {
        implicit val activeShards: ActiveShards = ActiveShards((new ShardSpecParser).parse("0,1 / 2"))
        val shoeboxClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val searchClient = inject[SearchServiceClient]
        val monitoredAwait = inject[MonitoredAwait]
        val (users, uris, bms, t0) = setup(shoeboxClient)

        val store = mkStore(uris)
        val (shardedUriGraphIndexer, _, _, userGraphIndexer, userGraphsSearcherFactory, mainSearcherFactory) = initIndexes(store)
        shardedUriGraphIndexer.update()
        userGraphIndexer.update()

        val uriGraphCommanderFactory = new URIGraphCommanderFactory(mainSearcherFactory)
        val metaProvider = new FeedMetaInfoProvider(shoeboxClient)
        val feedCommander = new FeedCommanderImpl(activeShards, userGraphsSearcherFactory, uriGraphCommanderFactory, searchClient, shoeboxClient, metaProvider, monitoredAwait)

        val feeds = feedCommander.getFeeds(users(0).id.get, 10)
        feeds.size === 2
        val (f0, f1) = (feeds(0), feeds(1))
        f0.uri.title.get === "4"
        f0.sharingUsers.map { _.firstName }.toSet === Set("u2")
        f0.totalKeepersSize === 2
        f0.firstKeptAt.getMillis() === t0.plusMinutes(7).getMillis()

        f1.uri.title.get === "1"
        f1.sharingUsers.map { _.firstName }.toSet === Set("u1", "u2")
        f1.totalKeepersSize === 3
        f1.firstKeptAt.getMillis() === t0.plusMinutes(1).getMillis()
      }
    }
  }
}
