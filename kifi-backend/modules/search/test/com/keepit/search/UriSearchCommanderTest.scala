package com.keepit.search

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.search.engine.LibraryQualityEvaluator
import com.keepit.search.test.SearchTestInjector
import org.specs2.mutable._
import com.keepit.common.akka.MonitoredAwait
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json.Json
import com.keepit.search.index.sharding.ActiveShards
import com.keepit.search.index.sharding.ShardSpecParser
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.search.augmentation.AugmentationCommanderImpl

class UriSearchCommanderTest extends Specification with SearchTestInjector with SearchTestHelper {

  implicit private val activeShards: ActiveShards = ActiveShards((new ShardSpecParser).parse("0,1 / 2"))

  "SearchCommander" should {
    "generate results in the correct json format" in {
      withInjector(helperModules: _*) { implicit injector =>
        val (users, uris) = initData(numUsers = 4, numUris = 9)
        val expectedUriToUserEdges = Seq(uris(0) -> Seq(users(0), users(1), users(2)), uris(1) -> Seq(users(1)), uris(2) -> Seq(users(2)), uris(3) -> Seq(users(3)))
        val allKeeps = saveBookmarksByURI(expectedUriToUserEdges)

        val store = mkStore(uris)
        val (shardedCollectionIndexer, indexer, userGraphIndexer, _, searchFactory, shardedKeepIndexer, libraryIndexer, libraryMembershipIndexer) = initIndexes(store)
        indexer.update() === uris.size
        Await.result((shardedKeepIndexer.asyncUpdate() zip libraryIndexer.asyncUpdate() zip libraryMembershipIndexer.asyncUpdate()), Duration(60, SECONDS))

        setConnections(Map(users(0).id.get -> Set(users(1).id.get)))
        userGraphIndexer.update()

        def myBookmarkExternalId = allKeeps.find { keep => keep.uriId == uris(0).id.get && keep.userId == users(0).id.get }.get.externalId

        val searchConfig = noBoostConfig.overrideWith("myBookmarkBoost" -> "2", "sharingBoostInNetwork" -> "0.5", "sharingBoostOutOfNetwork" -> "0.1")

        val languageCommander = new LanguageCommanderImpl(inject[DistributedSearchServiceClient], searchFactory, shardedKeepIndexer)
        val augmentationCommander = new AugmentationCommanderImpl(activeShards, shardedKeepIndexer, libraryIndexer, searchFactory, new LibraryQualityEvaluator(activeShards), inject[DistributedSearchServiceClient])
        val compatibilitySupport = new SearchBackwardCompatibilitySupport(libraryIndexer, augmentationCommander, shardedCollectionIndexer, inject[MonitoredAwait])

        val searchCommander = new UriSearchCommanderImpl(
          activeShards,
          searchFactory,
          languageCommander,
          inject[ArticleSearchResultStore],
          compatibilitySupport,
          inject[AirbrakeNotifier],
          inject[DistributedSearchServiceClient],
          inject[ShoeboxServiceClient],
          inject[MonitoredAwait])

        val res = searchCommander.search(
          userId = users(0).id.get,
          acceptLangs = Seq("en"),
          experiments = Set.empty,
          query = "keepit",
          filter = None,
          maxHits = 2,
          lastUUID = None,
          context = None,
          predefinedConfig = Some(searchConfig),
          withUriSummary = false)

        res.myTotal === 1
        res.friendsTotal === 1
        res.othersTotal === 2

        val expected = List( // without score, textScore, scoring
          Json.parse(s"""
            {
              "uriId":1,
              "bookmarkCount":3,
              "users":[${users(1).id.get}],
              "isMyBookmark":true,
              "isFriendsBookmark":true,
              "isPrivate":false,
              "bookmark":{
                "title":"a0",
                "url":"http://www.keepit.com/article0",
                "id":"${myBookmarkExternalId}",
                "matches":{"url":[[11,6]]}
              },
              "basicUsers":[{"id":"${users(1).externalId}","firstName":"foo1","lastName":"","pictureName":"0.jpg","username":"test1"}]
            }
          """),
          Json.parse(s"""
            {
              "uriId":2,
              "bookmarkCount":1,
              "users":[${users(1).id.get}],
              "isMyBookmark":false,
              "isFriendsBookmark":true,
              "isPrivate":false,
              "bookmark":{
                "title":"title1",
                "url":"http://www.keepit.com/article1",
                "matches":{"url":[[11,6]]}
              },
              "basicUsers":[{"id":"${users(1).externalId}","firstName":"foo1","lastName":"","pictureName":"0.jpg","username":"test1"}]
            }
          """)
        )

        res.hits.size === 2
        res.hits(0).json - "score" - "textScore" - "scoring" === expected(0)
        res.hits(1).json - "score" - "textScore" - "scoring" === expected(1)
      }
    }
  }
}
