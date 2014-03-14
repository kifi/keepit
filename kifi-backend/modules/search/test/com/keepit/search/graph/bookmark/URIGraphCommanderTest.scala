package com.keepit.search.graph.bookmark

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
import com.keepit.search.sharding.ActiveShards
import com.keepit.search.sharding.ActiveShardsSpecParser
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.VolatileIndexDirectoryImpl
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.search.sharding.ShardedURIGraphIndexer
import com.keepit.search.SearchTestHelper
import com.keepit.search.graph.URIList

class URIGraphCommanderTest extends Specification with SearchApplicationInjector with SearchTestHelper {
  "URIGraphCommander" should {
    "work and check authorization" in {
      running(new TestApplication(FakeShoeboxServiceModule())) {
        implicit val activeShards: ActiveShards = (new ActiveShardsSpecParser).parse(Some("0,1 / 2"))
        val uriGraphIndexers = activeShards.shards.map { shard =>
          val bookmarkStore = new BookmarkStore(new VolatileIndexDirectoryImpl, inject[AirbrakeNotifier])
          val uriGraphIndexer = new URIGraphIndexer(new VolatileIndexDirectoryImpl, bookmarkStore, inject[AirbrakeNotifier])
          (shard -> uriGraphIndexer)
        }
        val shardedUriGraphIndexer = new ShardedURIGraphIndexer(uriGraphIndexers.toMap, inject[ShoeboxServiceClient])
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]

        val (users, uris) = initData(numUsers = 2, numUris = 10)
        val user0_public = (0 until 10).filter(x => x < 5 && x % 2 == 0).map { i => (uris(i), Seq(users(0))) }.toSeq
        val user0_private = (0 until 10).filter(x => x >= 5 && x % 2 == 0).map { i => (uris(i), Seq(users(0))) }.toSeq
        val user1_public = (0 until 10).filter(x => x < 5 && x % 2 == 1).map { i => (uris(i), Seq(users(1))) }.toSeq
        val user1_private = (0 until 10).filter(x => x >= 5 && x % 2 == 1).map { i => (uris(i), Seq(users(1))) }.toSeq
        saveBookmarksByURI(user0_public)
        saveBookmarksByURI(user0_private, isPrivate = true)
        saveBookmarksByURI(user1_public)
        saveBookmarksByURI(user1_private, isPrivate = true)

        shardedUriGraphIndexer.update()

        val uriGraphCommander = new URIGraphCommanderImpl(RequestingUser(users(0).id.get), shardedUriGraphIndexer)
        val uriGraphCommander1 = new URIGraphCommanderImpl(RequestingUser(users(1).id.get), shardedUriGraphIndexer)
        val u0list = uriGraphCommander.getUserUriList(users(0).id.get, publicOnly = false)
        val u1list = uriGraphCommander1.getUserUriList(users(1).id.get, publicOnly = false)
        val shards = uriGraphCommander.getIndexShards
        shards.size == 2
        shards(0).contains(Id[NormalizedURI](1)) === false
        shards(0).contains(Id[NormalizedURI](2)) === true
        u0list(shards(0)).publicList === None
        u0list(shards(0)).privateList.get.ids.size === 0        // asymmetric behavior comes from URIGraphSearcher.getUserToUriEdgeSet()
        u0list(shards(1)).publicList.get.ids === Array(1, 3, 5)
        u0list(shards(1)).privateList.get.ids === Array(7, 9)

        u1list(shards(0)).publicList.get.ids === Array(2, 4)
        u1list(shards(0)).privateList.get.ids === Array(6, 8, 10)
        u1list(shards(1)).publicList === None
        u1list(shards(1)).privateList.get.ids.size === 0

        uriGraphCommander1.getUserUriList(users(0).id.get, publicOnly = false) should throwAn[NotAuthorizedURIGraphQueryException]
      }
    }
  }
}
