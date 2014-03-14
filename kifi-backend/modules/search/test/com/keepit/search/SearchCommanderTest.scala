package com.keepit.search

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.graph.bookmark._
import com.keepit.search.index.VolatileIndexDirectoryImpl
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.article.ArticleIndexer
import com.keepit.search.phrasedetector._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.test._
import org.specs2.mutable._
import play.api.test.Helpers._
import scala.math._
import com.keepit.search.spellcheck.SpellCorrector
import com.keepit.common.service.FortyTwoServices
import com.keepit.search.graph.collection._
import com.keepit.common.akka.MonitoredAwait
import scala.concurrent.Promise
import scala.Some
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import com.keepit.inject._
import com.keepit.shoebox.{FakeShoeboxServiceClientImpl, ShoeboxServiceClient}
import play.api.Play.current
import com.keepit.search.user.UserIndexer
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import akka.actor.ActorSystem
import play.api.libs.json.Json
import com.keepit.search.sharding.ActiveShards
import com.keepit.search.sharding.ActiveShardsSpecParser

class SearchCommanderTest extends Specification with SearchApplicationInjector with SearchTestHelper {

  implicit private val activeShards: ActiveShards = (new ActiveShardsSpecParser).parse(Some("0,1 / 2"))

  "SearchCommander" should {
    "generate results in the correct json format" in {
      running(application) {
        val (users, uris) = initData(numUsers = 4, numUris = 9)
        val expectedUriToUserEdges = Seq(uris(0) -> Seq(users(0), users(1), users(2)), uris(1) -> Seq(users(1)), uris(2) -> Seq(users(2)), uris(3) -> Seq(users(3)))
        saveBookmarksByURI(expectedUriToUserEdges)

        val store = mkStore(uris)
        val (graph, _, indexer, userGraphIndexer, userGraphsCommander, mainSearcherFactory) = initIndexes(store)
        graph.update()
        indexer.update() === uris.size

        setConnections(Map(users(0).id.get -> Set(users(1).id.get)))
        userGraphIndexer.update()
        val (friendsFuture, unfriendsFuture) = (userGraphsCommander.getConnectedUsersFuture(users(0).id.get), userGraphsCommander.getUnfriendedFuture(users(0).id.get))

        def myBookmarkExternalId = getBookmarkByUriAndUser(uris(0).id.get, users(0).id.get).get.externalId

        val searchConfigManager = new SearchConfigManager(None, inject[ShoeboxServiceClient], inject[MonitoredAwait])
        searchConfigManager.setUserConfig(users(0).id.get, noBoostConfig.overrideWith("myBookmarkBoost" -> "2", "sharingBoostInNetwork" -> "0.5", "sharingBoostOutOfNetwork" -> "0.1"))

        val searchCommander = new SearchCommanderImpl(
          activeShards,
          searchConfigManager,
          mainSearcherFactory,
          inject[ArticleSearchResultStore],
          inject[AirbrakeNotifier],
          inject[ShoeboxServiceClient],
          inject[MonitoredAwait],
          inject[FortyTwoServices])

        val res = searchCommander.search(
            userId = users(0).id.get,
            acceptLangs = Seq("en"),
            experiments = Set.empty,
            query = "keepit",
            filter = None,
            maxHits = 3,
            lastUUIDStr = None,
            context = None,
            predefinedConfig = None,
            start = None,
            end = None,
            tz = None,
            coll = None)

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
