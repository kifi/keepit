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

class MainSearcherTest extends Specification with SearchApplicationInjector with SearchTestHepler {

  "MainSearcher" should {
    "search and categorize using social graph" in {
      running(application) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        saveBookmarksByURI(expectedUriToUserEdges)

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)
        graph.update() === users.size
        indexer.run() === uris.size

        val numHitsPerCategory = 1000
        users.foreach{ user =>
          users.sliding(3).foreach{ friends =>
            val userId = user.id.get
            setConnections(Map(userId -> (friends.map(_.id.get).toSet - userId)))
            mainSearcherFactory.clear
            val mainSearcher = mainSearcherFactory(userId, "alldocs", english, numHitsPerCategory, SearchFilter.default(), allHitsConfig)
            val graphSearcher = mainSearcher.uriGraphSearcher
            val (myHits, friendsHits, othersHits, _) = mainSearcher.searchText(numHitsPerCategory)

            //println("----")
            val myUriIds = graphSearcher.getUserToUriEdgeSet(userId).destIdSet.map(_.id)
            myHits.size === min(myUriIds.size, numHitsPerCategory)
            myHits.foreach{ h =>
              //println("users:" + h)
              (myUriIds contains h.hit.id) === true
            }

            val friendsUriIds = friends.foldLeft(Set.empty[Long]){ (s, f) =>
              s ++ graphSearcher.getUserToUriEdgeSet(f.id.get).destIdSet.map(_.id)
            } -- myUriIds
            friendsHits.size === min(friendsUriIds.size, numHitsPerCategory)
            friendsHits.foreach{ h =>
              //println("friends:"+ h)
              (myUriIds contains h.hit.id) === false
              (friendsUriIds contains h.hit.id) === true
            }

            val othersUriIds = (uris.map(_.id.get.id).toSet) -- friendsUriIds -- myUriIds
            othersHits.size === min(othersUriIds.size, numHitsPerCategory)
            othersHits.foreach{ h =>
              //println("others:"+ h)
              (myUriIds contains h.hit.id) === false
              (friendsUriIds contains h.hit.id) === false
            }
          }
        }
        indexer.numDocs === uris.size
      }
    }

    "return a single list of hits" in {
      running(application) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        saveBookmarksByURI(expectedUriToUserEdges)

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === users.size
        indexer.run() === uris.size

        val numHitsToReturn = 7
        users.foreach{ user =>
          val userId = user.id.get
          //println("user:" + userId)
          users.sliding(3).foreach{ friends =>
            setConnections(Map(userId -> (friends.map(_.id.get).toSet - userId)))

            mainSearcherFactory.clear
            val mainSearcher = mainSearcherFactory(userId, "alldocs", english, numHitsToReturn, SearchFilter.default(), allHitsConfig)
            val graphSearcher = mainSearcher.uriGraphSearcher
            val res = mainSearcher.search()

            val myUriIds = graphSearcher.getUserToUriEdgeSet(userId).destIdSet
            val friendsUriIds = friends.foldLeft(Set.empty[Id[NormalizedURI]]){ (s, f) =>
              s ++ graphSearcher.getUserToUriEdgeSet(f.id.get).destIdSet
            } -- myUriIds
            val othersUriIds = (uris.map(_.id.get).toSet) -- friendsUriIds -- myUriIds

            var mCnt = 0
            var fCnt = 0
            var oCnt = 0
            res.hits.foreach{ h =>
              if (h.isMyBookmark) mCnt += 1
              else if (! h.users.isEmpty) fCnt += 1
              else {
                oCnt += 1
                h.bookmarkCount === graphSearcher.getUriToUserEdgeSet(h.uriId).size
              }
            }
            //println(hits)
            //println(List(mCnt, fCnt, oCnt)+ " <-- " + List(myUriIds.size, friendsUriIds.size, othersUriIds.size))
            (mCnt >= min(myUriIds.size, allHitsConfig.asInt("minMyBookmarks"))) === true
            fCnt === min(friendsUriIds.size, numHitsToReturn - mCnt)
            oCnt === (res.hits.size - mCnt - fCnt)
          }
        }
        indexer.numDocs === uris.size
      }
    }

    "search personal bookmark titles" in {
      running(application) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        saveBookmarksByURI(expectedUriToUserEdges, uniqueTitle = Some("personal title"))

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === users.size

        def run = {
          val numHitsToReturn = 100
          users.foreach{ user =>
            val userId = user.id.get
            //println("user:" + userId)
            setConnections(Map(userId -> (users.map(_.id.get).toSet - userId)))
            mainSearcherFactory.clear
            val mainSearcher = mainSearcherFactory(userId, "personal", english, numHitsToReturn, SearchFilter.default(), allHitsConfig)
            val graphSearcher = mainSearcher.uriGraphSearcher
            val res = mainSearcher.search()

            val myUriIds = graphSearcher.getUserToUriEdgeSet(userId).destIdSet

            var mCnt = 0
            var fCnt = 0
            var oCnt = 0
            res.hits.foreach{ h =>
              if (h.isMyBookmark) mCnt += 1
              else if (! h.users.isEmpty) fCnt += 1
              else {
                oCnt += 1
                h.bookmarkCount === graphSearcher.getUriToUserEdgeSet(h.uriId).size
              }
            }
            //println(res.hits)
            res.hits.map(h => h.uriId).toSet === myUriIds
            mCnt === myUriIds.size
            fCnt === 0
            oCnt === 0
          }
        }
        // before main indexing
        indexer.numDocs === 0
        run
        // after main indexing 3 docs
        indexer.run(3, 3) === 3
        run
        // after main indexing 6 docs
        indexer.run(3, 3) === 3
        run
        // after main indexing 9 docs
        indexer.run(3, 3) === 3
        run
        indexer.numDocs === uris.size
      }
    }

    "score using matches in a bookmark title and an article" in {
      running(application) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        saveBookmarksByURI(expectedUriToUserEdges, uniqueTitle = Some("personal title"))


        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === users.size
        indexer.run() === uris.size

        val numHitsToReturn = 100
        val userId = users(0).id.get
        //println("user:" + userId)
        setConnections(Map(userId -> (users.map(_.id.get).toSet - userId)))
        val mainSearcher = mainSearcherFactory(userId, "personal title3 content3 xyz", english, numHitsToReturn, SearchFilter.default(), noBoostConfig("myBookMarkBoost" -> "1.5"))
        val graphSearcher = mainSearcher.uriGraphSearcher

        val expected = (uris(3) :: ((uris.toList diff List(uris(3))).reverse)).map(_.id.get).toList
        val res = mainSearcher.search()

        val myUriIds = graphSearcher.getUserToUriEdgeSet(userId).destIdSet

        res.hits.map(h => h.uriId).toList === expected
      }
    }

    "paginate" in {
      running(application) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        saveBookmarksByURI(expectedUriToUserEdges)

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === users.size
        indexer.run() === uris.size

        val numHitsToReturn = 3
        val userId = Id[User](8)
        setConnections(Map(userId -> Set(Id[User](6))))
        var uriSeen = Set.empty[Long]

        var context = Some(IdFilterCompressor.fromSetToBase64(uriSeen))
        val mainSearcher = mainSearcherFactory(userId, "alldocs", english, numHitsToReturn, SearchFilter.default(context), allHitsConfig)
        val graphSearcher = mainSearcher.uriGraphSearcher
        val reachableUris = users.foldLeft(Set.empty[Long])((s, u) => s ++ graphSearcher.getUserToUriEdgeSet(u.id.get, publicOnly = true).destIdLongSet)

        var cnt = 0
        while (cnt < reachableUris.size && uriSeen.size < reachableUris.size) {
          cnt += 1
          context = Some(IdFilterCompressor.fromSetToBase64(uriSeen))
          val mainSearcher = mainSearcherFactory(userId, "alldocs", english, numHitsToReturn, SearchFilter.default(context), allHitsConfig)
          //println("---" + uriSeen + ":" + reachableUris)
          val res = mainSearcher.search()
          res.hits.foreach{ h =>
            //println(h)
            uriSeen.contains(h.uriId.id) === false
            uriSeen += h.uriId.id
          }
        }
        uriSeen.size === reachableUris.size
        indexer.numDocs === uris.size
      }
    }

    "boost recent bookmarks" in {
      running(application) {
        val (users, uris) = initData(numUsers = 1, numUris = 5)
        val Seq(user) = users
        val userId = user.id.get
        val bookmarks = saveBookmarksByUser(Seq((user, uris)))
        val bookmarkMap = bookmarks.map(b => (b.uriId -> b)).toMap

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === users.size
        indexer.run() === uris.size

        setConnections(Map(userId -> Set.empty[Id[User]]))

        val mainSearcher = mainSearcherFactory(userId, "alldocs", english, uris.size, SearchFilter.default(), noBoostConfig("recencyBoost" -> "1.0"))
        val res = mainSearcher.search()

        var lastTime = Long.MaxValue

        res.hits.map{ h => bookmarkMap(h.uriId) }.foreach{ b =>
          (b.createdAt.getMillis <= lastTime) === true
          lastTime = b.createdAt.getMillis
        }
        indexer.numDocs === uris.size
      }
    }

    "be able to cut the long tail" in {
      running(application) {
        val (users, uris) = initData(numUsers = 1, numUris = 10)
        val Seq(user) = users
        val userId = user.id.get
        saveBookmarksByUser(Seq((user, uris)))

        val store = {
          val sz = uris.size
          uris.zipWithIndex.foldLeft(new FakeArticleStore){ case (store, (uri, idx)) =>
            store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), "alldocs " * idx + "dummy" * (sz - idx)))
            store
          }
        }
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === users.size
        indexer.run() === uris.size
        setConnections(Map(userId -> Set.empty[Id[User]]))

        var mainSearcher = mainSearcherFactory(userId, "alldocs", english, uris.size, SearchFilter.default(), noBoostConfig)
        var res = mainSearcher.search()
        //println("Scores: " + res.hits.map(_.score))
        val sz = res.hits.size
        val maxScore = res.hits.head.score
        val minScore = res.hits(sz - 1).score
        val medianScore = res.hits(sz/2).score
        (minScore < medianScore && medianScore < maxScore) === true // this is a sanity check of test data

        val tailCuttingConfig = noBoostConfig("tailCutting" -> medianScore.toString)
        mainSearcher = mainSearcherFactory(userId, "alldocs", english, uris.size, SearchFilter.default(), tailCuttingConfig)
        res = mainSearcher.search()
        //println("Scores: " + res.hits.map(_.score))
        (res.hits.map(h => h.score).reduce((s1, s2) => min(s1, s2)) >= medianScore) === true
      }
    }

    "show own private bookmarks" in {
      running(application) {
        val (users, uris) = initData(numUsers = 2, numUris = 20)
        val user1 = users(0)
        val user2 = users(1)
        val (privateUris, publicUris) = uris.partition(_.id.get.id % 3 == 0)
        saveBookmarksByUser(Seq((user1, publicUris)), isPrivate = false)
        saveBookmarksByUser(Seq((user1, privateUris)), isPrivate = true)

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === 1
        indexer.run() === uris.size

        setConnections(Map(user1.id.get -> Set(user2.id.get)))

        val mainSearcher = mainSearcherFactory(user1.id.get, "alldocs", english, uris.size, SearchFilter.default(), noBoostConfig)
        val res = mainSearcher.search()

        res.hits.size === uris.size
      }
    }

    "not show friends private bookmarks" in {
      running(application) {
        val (users, uris) = initData(numUsers = 2, numUris = 20)
        val user1 = users(0)
        val user2 = users(1)
        val (privateUris, publicUris) = uris.partition(_.id.get.id % 3 == 0)
        saveBookmarksByUser(Seq((user2, publicUris)), isPrivate = false)
        saveBookmarksByUser(Seq((user2, privateUris)), isPrivate = true)

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === 1
        indexer.run() === uris.size

        setConnections(Map(user1.id.get -> Set(user2.id.get)))

        val mainSearcher = mainSearcherFactory(user1.id.get, "alldocs", english, uris.size, SearchFilter.friends(), noBoostConfig)
        val res = mainSearcher.search()

        val publicSet = publicUris.map(u => u.id.get).toSet
        val privateSet = privateUris.map(u => u.id.get).toSet
        res.hits.foreach{ h =>
          publicSet.contains(h.uriId) === true
          privateSet.contains(h.uriId) === false
        }
        res.hits.size === publicUris.size
      }
    }

    "search hits using a stemmed word" in {
      running(application) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        saveBookmarksByURI(expectedUriToUserEdges, uniqueTitle = Some("my books"))

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === users.size
        indexer.run() === uris.size

        val numHitsToReturn = 100
        val userId = users(0).id.get
        setConnections(Map(userId -> (users.map(_.id.get).toSet - userId)))
        val mainSearcher1 = mainSearcherFactory(userId, "document", english, numHitsToReturn, SearchFilter.default(), noBoostConfig)
        val res1 = mainSearcher1.search()
        (res1.hits.size > 0) === true

        val mainSearcher2 = mainSearcherFactory(userId, "book", english, numHitsToReturn, SearchFilter.default(), noBoostConfig)
        val res2 = mainSearcher2.search()
        (res2.hits.size > 0) === true
      }
    }

    "search within collections" in {
      running(application) {
        val (users, uris) = initData(numUsers = 2, numUris = 20)
        val user1 = users(0)
        val user2 = users(1)
        val bookmarks = saveBookmarksByUser(Seq((user1, uris), (user2, uris)))

        val (coll1set, _) = bookmarks.partition { b => b.userId == user1.id.get && b.uriId.id % 3 == 0 }
        val (coll2set, _) = bookmarks.partition { b => b.userId == user2.id.get && b.uriId.id % 3 == 1 }
        val Seq(coll1, coll2) = saveCollections(
          Collection(userId = user1.id.get, name = "coll1"),
          Collection(userId = user2.id.get, name = "coll2")
        )
        saveBookmarksToCollection(coll1.id.get, coll1set:_*)
        saveBookmarksToCollection(coll2.id.get, coll2set:_*)

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === 2
        indexer.run() === uris.size

        setConnections(Map(user1.id.get -> Set(user2.id.get)))

        val coll1Future = Promise.successful(Seq(coll1.id.get)).future
        val searchFilter1 = SearchFilter.mine(collectionsFuture = Some(coll1Future), monitoredAwait = inject[MonitoredAwait])
        val mainSearcher1 = mainSearcherFactory(user1.id.get, "alldocs", english, uris.size, searchFilter1, noBoostConfig)
        val res1 = mainSearcher1.search()

        res1.hits.size == coll1set.size
        res1.hits.foreach{ _.uriId.id % 3 === 0 }

        val coll2Future = Promise.successful(Seq(coll2.id.get)).future
        val searchFilter2 = SearchFilter.mine(collectionsFuture = Some(coll2Future), monitoredAwait = inject[MonitoredAwait])
        val mainSearcher2 = mainSearcherFactory(user1.id.get, "alldocs", english, uris.size, searchFilter2, noBoostConfig)
        val res2 = mainSearcher2.search()

        res2.hits.size == coll2set.size
        res2.hits.foreach{ _.uriId.id % 3 === 1 }

        val coll3Future = Promise.successful(Seq(coll1.id.get, coll2.id.get)).future
        val searchFilter3 = SearchFilter.mine(collectionsFuture = Some(coll3Future), monitoredAwait = inject[MonitoredAwait])
        val mainSearcher3 = mainSearcherFactory(user1.id.get, "alldocs", english, uris.size, searchFilter3, noBoostConfig)
        val res3 = mainSearcher3.search()

        res3.hits.size == (coll1set.size + coll2set.size)
      }
    }

    "search thru collection names" in {
      running(application) {
        val (users, uris) = initData(numUsers = 1, numUris = 20)
        val user1 = users(0)
        val bookmarks = saveBookmarksByUser(Seq((user1, uris)))

        val (coll1set, _) = bookmarks.partition { b => b.userId == user1.id.get && b.uriId.id % 3 == 0 }
        val (coll2set, _) = bookmarks.partition { b => b.userId == user1.id.get && b.uriId.id % 3 == 1 }
        val Seq(coll1, coll2) = saveCollections(
          Collection(userId = user1.id.get, name = "mycoll"),
          Collection(userId = user1.id.get, name = "different mycoll")
        )
        saveBookmarksToCollection(coll1.id.get, coll1set:_*)
        saveBookmarksToCollection(coll2.id.get, coll2set:_*)

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === 1
        indexer.run() === uris.size

        val searchFilter = SearchFilter.mine(monitoredAwait = inject[MonitoredAwait])

        val mainSearcher1 = mainSearcherFactory(user1.id.get, "mycoll", english, uris.size, searchFilter, noBoostConfig)
        val res1 = mainSearcher1.search()
        val expected1 = coll1set.toSet

        res1.hits.size == expected1.size
        res1.hits.map(_.uriId.id).toSet === expected1.map(_.uriId.id).toSet

        val mainSearcher2 = mainSearcherFactory(user1.id.get, "different mycoll", english, uris.size, searchFilter, noBoostConfig)
        val res2 = mainSearcher2.search()
        val expected2 = (coll1set ++ coll2set).toSet

        res2.hits.size == expected2.size
        res2.hits.map(_.uriId.id).toSet === expected2.map(_.uriId.id).toSet

        val mainSearcher3 = mainSearcherFactory(user1.id.get, "different", english, uris.size, searchFilter, noBoostConfig)
        val res3 = mainSearcher3.search()

        res3.hits.size == 0
      }
    }
  }
}
