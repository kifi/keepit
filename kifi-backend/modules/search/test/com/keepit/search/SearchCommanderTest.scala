package com.keepit.search

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.graph.BookmarkStore
import com.keepit.search.index.VolatileIndexDirectoryImpl
import com.keepit.search.graph.CollectionNameIndexer
import index.{FakePhraseIndexer, DefaultAnalyzer, ArticleIndexer}
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
import org.apache.lucene.index.IndexWriterConfig
import com.keepit.search.graph.{URIGraphImpl, URIGraphIndexer}
import org.apache.lucene.util.Version
import com.keepit.search.graph.CollectionIndexer
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

class SearchCommanderTest extends Specification with SearchApplicationInjector with SearchTestHepler {

  "SearchCommander" should {
    "generate results the incorrect json format" in {
      running(application) {
        val (users, uris) = initData(numUsers = 3, numUris = 9)
        val expectedUriToUserEdges = Seq(uris(0) -> Seq(users(0), users(1), users(2)), uris(1) -> Seq(users(1)))
        saveBookmarksByURI(expectedUriToUserEdges)

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)
        graph.update() === users.size
        indexer.update() === uris.size

        setConnections(Map(users(0).id.get -> Set(users(1).id.get)))

        def myBookmarkExternalId = getBookmarkByUriAndUser(uris(0).id.get, users(0).id.get).get.externalId

        val searchConfigManager = new SearchConfigManager(None, inject[ShoeboxServiceClient], inject[MonitoredAwait])
        searchConfigManager.setUserConfig(users(0).id.get, noBoostConfig("myBookmarkBoost" -> "2", "sharingBoostInNetwork" -> "0.5", "sharingBoostOutOfNetwork" -> "0.1"))

        val searchCommander = new SearchCommanderImpl(
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
            noSearchExperiments = false,
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
        res.othersTotal === 7

        val expected = List(
          Json.parse(s"""
            {
              "uriId":1,
              "bookmarkCount":3,
              "users":[${users(1).id.get}],
              "score":2.0015265941619873,
              "scoring":{
                "textScore":0.06511083990335464,
                "normalizedTextScore":1.0,
                "bookmarkScore":0.6666666269302368,
                "recencyScore":0.0,
                "boostedTextScore":2.0,
                "boostedBookmarkScore":0.3333333134651184,
                "boostedRecencyScore":0.0,
                "usefulPage":null
              },
              "isMyBookmark":true,
              "isFriendsBookmark":true,
              "isPrivate":false,
              "bookmark":{
                "title":"a0",
                "url":"http://www.keepit.com/article0",
                "id":"${myBookmarkExternalId}",
                "matches":{"url":[[11,6]]}
              },
              "basicUsers":[{"id":"${users(1).externalId}","firstName":"foo1","lastName":"","pictureName":"fake.jpg"}]
            }
          """),
          Json.parse(s"""
            {
              "uriId":2,
              "bookmarkCount":1,
              "users":[${users(1).id.get}],
              "score":1.0007957220077515,
              "scoring":{
                "textScore":0.06511083990335464,
                "normalizedTextScore":1.0,
                "bookmarkScore":0.34749501943588257,
                "recencyScore":0.0,
                "boostedTextScore":1.0,
                "boostedBookmarkScore":0.17374750971794128,
                "boostedRecencyScore":0.0,
                "usefulPage":null
              },
              "isMyBookmark":false,
              "isFriendsBookmark":true,
              "isPrivate":false,
              "bookmark":{
                "title":"title1",
                "url":"http://www.keepit.com/article1",
                "matches":{"url":[[11,6]]}
              },
              "basicUsers":[{"id":"${users(1).externalId}","firstName":"foo1","lastName":"","pictureName":"fake.jpg"}]
            }
          """)
        )

        res.hits.size === 2
        res.hits(0).json === expected(0)
        res.hits(1).json === expected(1)
      }
    }
  }
}
