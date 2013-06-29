package com.keepit.search

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.graph.BookmarkStore
import index.{FakePhraseIndexer, DefaultAnalyzer, ArticleIndexer}
import com.keepit.search.phrasedetector._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.common.db._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.time._
import com.keepit.test._
import org.specs2.mutable._
import play.api.test.Helpers._
import scala.math._
import com.keepit.search.query.parser.FakeSpellCorrector
import com.keepit.common.service.FortyTwoServices
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.RAMDirectory
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

class MainSearcherTest extends Specification {

  val resultClickBuffer  = new InMemoryResultClickTrackerBuffer(1000)
  val resultClickTracker = new ResultClickTracker(new ProbablisticLRU(resultClickBuffer, 8, Int.MaxValue)(None))

  def initData(numUsers: Int, numUris: Int) = {
    val users = (0 until numUsers).map {n => User(firstName = "foo" + n, lastName = "")}.toList
    val uris =   (0 until numUris).map {n => NormalizedURIFactory(title = "a" + n,
        url = "http://www.keepit.com/article" + n, state = SCRAPED)}.toList
    val fakeShoeboxClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    (fakeShoeboxClient.saveUsers(users:_*), fakeShoeboxClient.saveURIs(uris:_*))
  }

  def initIndexes(store: ArticleStore) = {
    val articleConfig = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    val bookmarkStoreConfig = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    val graphConfig = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    val collectConfig = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    val articleIndexer = new ArticleIndexer(new RAMDirectory, articleConfig, store, inject[HealthcheckPlugin], inject[ShoeboxServiceClient])
    val bookmarkStore = new BookmarkStore(new RAMDirectory, bookmarkStoreConfig, inject[HealthcheckPlugin], inject[ShoeboxServiceClient])
    val uriGraph = new URIGraphImpl(
      new URIGraphIndexer(new RAMDirectory, graphConfig, bookmarkStore, inject[HealthcheckPlugin], inject[ShoeboxServiceClient]),
      new CollectionIndexer(new RAMDirectory, collectConfig, inject[HealthcheckPlugin], inject[ShoeboxServiceClient]),
      inject[ShoeboxServiceClient],
      inject[MonitoredAwait])
    implicit val clock = inject[Clock]
    implicit val fortyTwoServices = inject[FortyTwoServices]

    val mainSearcherFactory = new MainSearcherFactory(
      articleIndexer,
      uriGraph,
      new MainQueryParserFactory(new PhraseDetector(new FakePhraseIndexer())),
      resultClickTracker,
      inject[BrowsingHistoryBuilder],
      inject[ClickHistoryBuilder],
      inject[ShoeboxServiceClient],
      inject[FakeSpellCorrector],
      inject[MonitoredAwait],
      clock,
      fortyTwoServices)
    (uriGraph, articleIndexer, mainSearcherFactory)
  }

  def mkStore(uris: Seq[NormalizedURI]) = {
    uris.zipWithIndex.foldLeft(new FakeArticleStore){ case (store, (uri, idx)) =>
      store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), "content%d alldocs documents".format(idx)))
      store
    }
  }

  def mkArticle(normalizedUriId: Id[NormalizedURI], title: String, content: String) = {
    Article(
      id = normalizedUriId,
      title = title,
      description = None,
      media = None,
      content = content,
      scrapedAt = currentDateTime,
      httpContentType = Some("text/html"),
      httpOriginalContentCharset = Option("UTF-8"),
      state = SCRAPED,
      message = None,
      titleLang = Some(Lang("en")),
      contentLang = Some(Lang("en")))
  }

  def setConnections(connections: Map[Id[User], Set[Id[User]]]) {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].clearUserConnections(connections.keys.toSeq:_*)
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveConnections(connections)
  }

  def saveCollections(collections: Collection*): Seq[Collection] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveCollections(collections:_*)
  }

  def saveBookmarksToCollection(collectionId: Id[Collection], bookmarks: Bookmark*) {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarksToCollection(collectionId, bookmarks:_*)
  }

  def saveBookmarksByURI(edgesByURI: Seq[(NormalizedURI, Seq[User])], uniqueTitle: Option[String] = None, isPrivate: Boolean = false): Seq[Bookmark] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarksByURI(edgesByURI, uniqueTitle, isPrivate, source)
  }

  def saveBookmarksByUser(edgesByUser: Seq[(User, Seq[NormalizedURI])], uniqueTitle: Option[String] = None, isPrivate: Boolean = false): Seq[Bookmark] = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveBookmarksByUser(edgesByUser, uniqueTitle, isPrivate, source)
  }

  val source = BookmarkSource("test")
  val defaultConfig = new SearchConfig(SearchConfig.defaultParams)
  val noBoostConfig = defaultConfig(
    "myBookmarkBoost" -> "1", "sharingBoostInNetwork" -> "0", "sharingBoostOutOfNetwork" -> "0",
    "recencyBoost" -> "0", "proximityBoost" -> "0", "semanticBoost" -> "0",
    "percentMatch" -> "0", "tailCutting" -> "0", "dampingByRank" -> "false")
  val allHitsConfig = defaultConfig("tailCutting" -> "0")

  implicit val lang = Lang("en")

  "MainSearcher" should {
    "search and categorize using social graph" in {
      running(new SearchApplication().withShoeboxServiceModule) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        saveBookmarksByURI(expectedUriToUserEdges)

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)
        val clickBoosts = resultClickTracker.getBoosts(Id[User](0), "", 1.0f)
        graph.update() === users.size
        indexer.run() === uris.size

        val numHitsPerCategory = 1000
        users.foreach{ user =>
          users.sliding(3).foreach{ friends =>
            val userId = user.id.get
            setConnections(Map(userId -> (friends.map(_.id.get).toSet - userId)))
            mainSearcherFactory.clear
            val mainSearcher = mainSearcherFactory(userId, SearchFilter.default(), allHitsConfig)
            val graphSearcher = mainSearcher.uriGraphSearcher
            val (myHits, friendsHits, othersHits, _) = mainSearcher.searchText("alldocs", numHitsPerCategory, clickBoosts)

            //println("----")
            val myUriIds = graphSearcher.getUserToUriEdgeSet(userId).destIdSet.map(_.id)
            myHits.size === min(myUriIds.size, numHitsPerCategory)
            myHits.foreach{ h =>
              //println("users:" + h)
              (myUriIds contains h.id) === true
            }

            val friendsUriIds = friends.foldLeft(Set.empty[Long]){ (s, f) =>
              s ++ graphSearcher.getUserToUriEdgeSet(f.id.get).destIdSet.map(_.id)
            } -- myUriIds
            friendsHits.size === min(friendsUriIds.size, numHitsPerCategory)
            friendsHits.foreach{ h =>
              //println("friends:"+ h)
              (myUriIds contains h.id) === false
              (friendsUriIds contains h.id) === true
            }

            val othersUriIds = (uris.map(_.id.get.id).toSet) -- friendsUriIds -- myUriIds
            othersHits.size === min(othersUriIds.size, numHitsPerCategory)
            othersHits.foreach{ h =>
              //println("others:"+ h)
              (myUriIds contains h.id) === false
              (friendsUriIds contains h.id) === false
            }
          }
        }
        indexer.numDocs === uris.size
      }
    }

    "return a single list of hits" in {
      running(new SearchApplication().withShoeboxServiceModule) {
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
            val mainSearcher = mainSearcherFactory(userId, SearchFilter.default(), allHitsConfig)
            val graphSearcher = mainSearcher.uriGraphSearcher
            val res = mainSearcher.search("alldocs", numHitsToReturn, None)

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
      running(new SearchApplication().withShoeboxServiceModule) {
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
            val mainSearcher = mainSearcherFactory(userId, SearchFilter.default(), allHitsConfig)
            val graphSearcher = mainSearcher.uriGraphSearcher
            val res = mainSearcher.search("personal", numHitsToReturn, None)

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
      running(new SearchApplication().withShoeboxServiceModule) {
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
        val mainSearcher = mainSearcherFactory(userId, SearchFilter.default(), noBoostConfig("myBookMarkBoost" -> "1.5"))
        val graphSearcher = mainSearcher.uriGraphSearcher

        val expected = (uris(3) :: ((uris.toList diff List(uris(3))).reverse)).map(_.id.get).toList
        val res = mainSearcher.search("personal title3 content3 xyz", numHitsToReturn, None)

        val myUriIds = graphSearcher.getUserToUriEdgeSet(userId).destIdSet

        res.hits.map(h => h.uriId).toList === expected
      }
    }

    "paginate" in {
      running(new SearchApplication().withShoeboxServiceModule) {
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
        val mainSearcher = mainSearcherFactory(userId, SearchFilter.default(context), allHitsConfig)
        val graphSearcher = mainSearcher.uriGraphSearcher
        val reachableUris = users.foldLeft(Set.empty[Long])((s, u) => s ++ graphSearcher.getUserToUriEdgeSet(u.id.get, publicOnly = true).destIdLongSet)

        var uuid : Option[ExternalId[ArticleSearchResultRef]] = None
        var cnt = 0
        while (cnt < reachableUris.size && uriSeen.size < reachableUris.size) {
          cnt += 1
          context = Some(IdFilterCompressor.fromSetToBase64(uriSeen))
          val mainSearcher = mainSearcherFactory(userId, SearchFilter.default(context), allHitsConfig)
          //println("---" + uriSeen + ":" + reachableUris)
          val res = mainSearcher.search("alldocs", numHitsToReturn, uuid)
          res.hits.foreach{ h =>
            //println(h)
            uriSeen.contains(h.uriId.id) === false
            uriSeen += h.uriId.id
          }
          uuid = Some(res.uuid)
        }
        uriSeen.size === reachableUris.size
        indexer.numDocs === uris.size
      }
    }

    "boost recent bookmarks" in {
      running(new SearchApplication().withShoeboxServiceModule) {
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

        val mainSearcher = mainSearcherFactory(userId, SearchFilter.default(), noBoostConfig("recencyBoost" -> "1.0"))
        val res = mainSearcher.search("alldocs", uris.size, None)

        var lastTime = Long.MaxValue

        res.hits.map{ h => bookmarkMap(h.uriId) }.foreach{ b =>
          (b.createdAt.getMillis <= lastTime) === true
          lastTime = b.createdAt.getMillis
        }
        indexer.numDocs === uris.size
      }
    }

    "be able to cut the long tail" in {
      running(new SearchApplication().withShoeboxServiceModule) {
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

        var mainSearcher = mainSearcherFactory(userId, SearchFilter.default(), noBoostConfig)
        var res = mainSearcher.search("alldocs", uris.size, None)
        //println("Scores: " + res.hits.map(_.score))
        val sz = res.hits.size
        val maxScore = res.hits.head.score
        val minScore = res.hits(sz - 1).score
        val medianScore = res.hits(sz/2).score
        (minScore < medianScore && medianScore < maxScore) === true // this is a sanity check of test data

        val tailCuttingConfig = noBoostConfig("tailCutting" -> medianScore.toString)
        mainSearcher = mainSearcherFactory(userId, SearchFilter.default(), tailCuttingConfig)
        res = mainSearcher.search("alldocs", uris.size, None)
        //println("Scores: " + res.hits.map(_.score))
        (res.hits.map(h => h.score).reduce((s1, s2) => min(s1, s2)) >= medianScore) === true
      }
    }

    "show own private bookmarks" in {
      running(new SearchApplication().withShoeboxServiceModule) {
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

        val mainSearcher = mainSearcherFactory(user1.id.get, SearchFilter.default(), noBoostConfig)
        val res = mainSearcher.search("alldocs", uris.size, None)

        res.hits.size === uris.size
      }
    }

    "not show friends private bookmarks" in {
      running(new SearchApplication().withShoeboxServiceModule) {
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

        val mainSearcher = mainSearcherFactory(user1.id.get, SearchFilter.default(), noBoostConfig)
        val res = mainSearcher.search("alldocs", uris.size, None)

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
      running(new SearchApplication().withShoeboxServiceModule) {
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
        val mainSearcher = mainSearcherFactory(userId, SearchFilter.default(), noBoostConfig)
        val graphSearcher = mainSearcher.uriGraphSearcher

        var res = mainSearcher.search("document", numHitsToReturn, None)
        (res.hits.size > 0) === true

        res = mainSearcher.search("book", numHitsToReturn, None)
        (res.hits.size > 0) === true
      }
    }

    "search within collections" in {
      running(new SearchApplication().withShoeboxServiceModule) {
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
        val mainSearcher1 = mainSearcherFactory(user1.id.get, searchFilter1, noBoostConfig)
        val res1 = mainSearcher1.search("alldocs", uris.size, None)

        res1.hits.size == coll1set.size
        res1.hits.foreach{ _.uriId.id % 3 === 0 }

        val coll2Future = Promise.successful(Seq(coll2.id.get)).future
        val searchFilter2 = SearchFilter.mine(collectionsFuture = Some(coll2Future), monitoredAwait = inject[MonitoredAwait])
        val mainSearcher2 = mainSearcherFactory(user1.id.get, searchFilter2, noBoostConfig)
        val res2 = mainSearcher2.search("alldocs", uris.size, None)

        res2.hits.size == coll2set.size
        res2.hits.foreach{ _.uriId.id % 3 === 1 }

        val coll3Future = Promise.successful(Seq(coll1.id.get, coll2.id.get)).future
        val searchFilter3 = SearchFilter.mine(collectionsFuture = Some(coll3Future), monitoredAwait = inject[MonitoredAwait])
        val mainSearcher3 = mainSearcherFactory(user1.id.get, searchFilter3, noBoostConfig)
        val res3 = mainSearcher3.search("alldocs", uris.size, None)

        res3.hits.size == (coll1set.size + coll2set.size)
      }
    }
  }
}
