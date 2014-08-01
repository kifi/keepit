package com.keepit.search

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.test._
import org.specs2.mutable._
import play.api.test.Helpers._
import com.keepit.common.akka.MonitoredAwait
import scala.Some
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json.Json
import com.keepit.search.sharding.ActiveShards
import com.keepit.search.sharding.ShardSpecParser

class SearchCommanderTest extends Specification with SearchTestInjector with SearchTestHelper {

  implicit private val activeShards: ActiveShards = ActiveShards((new ShardSpecParser).parse("0,1 / 2"))

  "SearchCommander" should {
    "generate results in the correct json format" in {
      withInjector(helperModules: _*) { implicit injector =>
        val (users, uris) = initData(numUsers = 4, numUris = 9)
        val expectedUriToUserEdges = Seq(uris(0) -> Seq(users(0), users(1), users(2)), uris(1) -> Seq(users(1)), uris(2) -> Seq(users(2)), uris(3) -> Seq(users(3)))
        saveBookmarksByURI(expectedUriToUserEdges)

        val store = mkStore(uris)
        val (graph, _, indexer, userGraphIndexer, userGraphsSearcherFactory, mainSearcherFactory) = initIndexes(store)
        graph.update()
        indexer.update() === uris.size

        setConnections(Map(users(0).id.get -> Set(users(1).id.get)))
        userGraphIndexer.update()

        def myBookmarkExternalId = getBookmarkByUriAndUser(uris(0).id.get, users(0).id.get).get.externalId

        val searchConfig = noBoostConfig.overrideWith("myBookmarkBoost" -> "2", "sharingBoostInNetwork" -> "0.5", "sharingBoostOutOfNetwork" -> "0.1")

        val searchCommander = new SearchCommanderImpl(
          activeShards,
          mainSearcherFactory,
          inject[ArticleSearchResultStore],
          inject[AirbrakeNotifier],
          inject[SearchServiceClient],
          inject[ShoeboxServiceClient],
          inject[MonitoredAwait])

        val res = searchCommander.search(
          userId = users(0).id.get,
          acceptLangs = Seq("en"),
          experiments = Set.empty,
          query = "keepit",
          filter = None,
          maxHits = 3,
          lastUUIDStr = None,
          context = None,
          predefinedConfig = Some(searchConfig),
          start = None,
          end = None,
          tz = None,
          coll = None,
          withUriSummary = false)

        res.myTotal === 1
        res.friendsTotal === 1
        res.othersTotal === 2

        val expected = List( // with neither score nor scoring
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
              "basicUsers":[{"id":"${users(1).externalId}","firstName":"foo1","lastName":"","pictureName":"0.jpg"}]
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
              "basicUsers":[{"id":"${users(1).externalId}","firstName":"foo1","lastName":"","pictureName":"0.jpg"}]
            }
          """)
        )

        res.hits.size === 2
        res.hits(0).json - "score" - "scoring" === expected(0)
        res.hits(1).json - "score" - "scoring" === expected(1)
      }
    }
  }
}
