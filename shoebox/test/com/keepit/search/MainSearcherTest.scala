package com.keepit.search

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphSearcher
import com.keepit.search.graph.URIList
import index.{FakePhraseIndexer, Indexable, DefaultAnalyzer, ArticleIndexer}
import com.keepit.search.phrasedetector._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.inject._
import com.keepit.test._
import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import org.apache.lucene.store.RAMDirectory
import scala.math._
import scala.util.Random
import com.keepit.inject._
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version

class MainSearcherTest extends Specification with DbRepos {

  val resultClickTracker = ResultClickTracker(8)
  val browsingHistoryTracker = running(new EmptyApplication()) {
    BrowsingHistoryTracker(3067, 2, 1)
  }
  val clickHistoryTracker = running(new EmptyApplication()) {
    ClickHistoryTracker(307, 2, 1)
  }

  def initData(numUsers: Int, numUris: Int) = db.readWrite { implicit s =>
    ((0 until numUsers).map(n => userRepo.save(User(firstName = "foo" + n, lastName = ""))).toList,
     (0 until numUris).map(n => uriRepo.save(NormalizedURIFactory(title = "a" + n,
       url = "http://www.keepit.com/article" + n, state = SCRAPED))).toList)
  }

  def initIndexes(store: ArticleStore) = {
    val articleIndexer = ArticleIndexer(new RAMDirectory, store)
    val uriGraph = URIGraph(new RAMDirectory)
    val mainSearcherFactory = new MainSearcherFactory(
        articleIndexer,
        uriGraph,
        new MainQueryParserFactory(new PhraseDetector(new FakePhraseIndexer())),
        resultClickTracker,
        browsingHistoryTracker,
        clickHistoryTracker)
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
        content = content,
        scrapedAt = currentDateTime,
        httpContentType = Some("text/html"),
        httpOriginalContentCharset = Option("UTF-8"),
        state = SCRAPED,
        message = None,
        titleLang = Some(Lang("en")),
        contentLang = Some(Lang("en")))
  }

  val source = BookmarkSource("test")

  val defaultConfig = new SearchConfig(SearchConfig.defaultParams)
  val noBoostConfig = defaultConfig("myBookmarkBoost" -> "1", "sharingBoostInNetwork" -> "0", "sharingBoostOutOfNetwork" -> "0",
                                    "recencyBoost" -> "0", "proximityBoost" -> "0", "semanticBoost" -> "0",
                                    "percentMatch" -> "0", "tailCutting" -> "0", "dumpingByRank" -> "false")
  val allHitsConfig = defaultConfig("tailCutting" -> "0")

  implicit val lang = Lang("en")

  "MainSearcher" should {
    "search and categorize using social graph" in {
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        val bookmarks = db.readWrite { implicit s =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              val url1 = urlRepo.save(urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))))
              bookmarkRepo.save(BookmarkFactory(title = uri.title.get, url = url1,  uriId = uri.id.get, userId = user.id.get, source = source))
            }
          }
        }

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)
        val clickBoosts = resultClickTracker.getBoosts(Id[User](0), "", 1.0f)
        graph.update() === users.size
        indexer.run() === uris.size

        val numHitsPerCategory = 1000
        users.foreach{ user =>
          users.sliding(3).foreach{ friends =>
            val userId = user.id.get
            val friendIds = friends.map(_.id.get).toSet - userId
            val mainSearcher = mainSearcherFactory(userId, friendIds, SearchFilter.default(), allHitsConfig)
            val graphSearcher = mainSearcher.uriGraphSearcher
            val (myHits, friendsHits, othersHits, parsedQuery) = mainSearcher.searchText("alldocs", numHitsPerCategory, clickBoosts)(Lang("en"))

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
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        val bookmarks = db.readWrite { implicit session =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              val url1 = urlRepo.save(urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))))
              bookmarkRepo.save(BookmarkFactory(title = uri.title.get, url = url1,  uriId = uri.id.get, userId = user.id.get, source = source))
            }
          }
        }

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === users.size
        indexer.run() === uris.size

        val numHitsToReturn = 7
        users.foreach{ user =>
          val userId = user.id.get
          //println("user:" + userId)
          users.sliding(3).foreach{ friends =>
            val friendIds = friends.map(_.id.get).toSet - userId

            val mainSearcher = mainSearcherFactory(userId, friendIds, SearchFilter.default(), allHitsConfig)
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
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        val bookmarks = db.readWrite {implicit s =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              val url1 = urlRepo.save(urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))))
              bookmarkRepo.save(BookmarkFactory(title = "personal title", url = url1,  uriId = uri.id.get, userId = user.id.get, source = source))
            }
          }
        }

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === users.size

        def run = {
          val numHitsToReturn = 100
          users.foreach{ user =>
            val userId = user.id.get
            //println("user:" + userId)
            val friendIds = users.map(_.id.get).toSet - userId
            val mainSearcher = mainSearcherFactory(userId, friendIds, SearchFilter.default(), allHitsConfig)
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
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        val bookmarks = db.readWrite { implicit s =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              val url1 = urlRepo.save(urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))))
              bookmarkRepo.save(BookmarkFactory(title = "personal title", url = url1,  uriId = uri.id.get, userId = user.id.get, source = source))
            }
          }
        }

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === users.size
        indexer.run() === uris.size

        val numHitsToReturn = 100
        val userId = users(0).id.get
        //println("user:" + userId)
        val friendIds = users.map(_.id.get).toSet - userId
        val mainSearcher = mainSearcherFactory(userId, friendIds, SearchFilter.default(), noBoostConfig("myBookMarkBoost" -> "1.5"))
        val graphSearcher = mainSearcher.uriGraphSearcher

        val expected = (uris(3) :: ((uris diff List(uris(3))).reverse)).map(_.id.get).toList
        val res = mainSearcher.search("personal title3 content3 xyz", numHitsToReturn, None)

        val myUriIds = graphSearcher.getUserToUriEdgeSet(userId).destIdSet

        res.hits.map(h => h.uriId).toList === expected
      }
    }

    "paginate" in {
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        val bookmarks = db.readWrite { implicit s =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              val url1 = urlRepo.save(urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))))
              bookmarkRepo.save(BookmarkFactory(title = uri.title.get, url = url1,  uriId = uri.id.get, userId = user.id.get, source = source))
            }
          }
        }

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === users.size
        indexer.run() === uris.size

        val numHitsToReturn = 3
        val userId = Id[User](8)

        val friendIds = Set(Id[User](6))
        var uriSeen = Set.empty[Long]

        val mainSearcher = mainSearcherFactory(userId, friendIds, SearchFilter.default(uriSeen), allHitsConfig)
        val graphSearcher = mainSearcher.uriGraphSearcher
        val reachableUris = users.foldLeft(Set.empty[Long])((s, u) => s ++ graphSearcher.getUserToUriEdgeSet(u.id.get, publicOnly = true).destIdLongSet)

        var uuid : Option[ExternalId[ArticleSearchResultRef]] = None
        var cnt = 0
        while (cnt < reachableUris.size && uriSeen.size < reachableUris.size) {
          cnt += 1
          val mainSearcher = mainSearcherFactory(userId, friendIds, SearchFilter.default(uriSeen), allHitsConfig)
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
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 1, numUris = 5)
        val userId = users.head.id.get
        val now = currentDateTime
        val rand = new Random

        val bookmarkMap = db.readWrite { implicit s =>
          uris.foldLeft(Map.empty[Id[NormalizedURI], Bookmark]){ (m, uri) =>
            val createdAt = now.minusHours(rand.nextInt(100))
            val uriId = uri.id.get
            val url1 = urlRepo.save(urlRepo.get(uri.url).getOrElse(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
            val bookmark = bookmarkRepo.save(BookmarkFactory(title = uri.title.get, url = url1,  uriId = uri.id.get, userId = userId, source = source))
            m + (uriId -> bookmark)
          }
        }

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === users.size
        indexer.run() === uris.size

        val mainSearcher = mainSearcherFactory(userId, Set.empty[Id[User]], SearchFilter.default(), noBoostConfig("recencyBoost" -> "1.0"))
        val res = mainSearcher.search("alldocs", uris.size, None)

        var lastTime = Long.MaxValue

        res.hits.map{ h => bookmarkMap(h.uriId) }.foreach{ b =>
          b.createdAt.getMillis <= lastTime === true
         lastTime = b.createdAt.getMillis
        }
        indexer.numDocs === uris.size
      }
    }

    "be able to cut the long tail" in {
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 1, numUris = 10)
        val userId = users.head.id.get

        val bookmarks = db.readWrite { implicit s =>
          uris.map{ uri =>
            val url1 = urlRepo.save(urlRepo.get(uri.url).getOrElse(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
            bookmarkRepo.save(BookmarkFactory(title = uri.title.get, url = url1,  uriId = uri.id.get, userId = userId, source = source))
          }
        }

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

        var mainSearcher = mainSearcherFactory(userId, Set.empty[Id[User]], SearchFilter.default(), noBoostConfig)
        var res = mainSearcher.search("alldocs", uris.size, None)
        //println("Scores: " + res.hits.map(_.score))
        val sz = res.hits.size
        val maxScore = res.hits.head.score
        val minScore = res.hits(sz - 1).score
        val medianScore = res.hits(sz/2).score
        (minScore < medianScore && medianScore < maxScore) === true // this is a sanity check of test data

        val tailCuttingConfig = noBoostConfig("tailCutting" -> medianScore.toString)
        mainSearcher = mainSearcherFactory(userId, Set.empty[Id[User]], SearchFilter.default(), tailCuttingConfig)
        res = mainSearcher.search("alldocs", uris.size, None)
        //println("Scores: " + res.hits.map(_.score))
        res.hits.map(h => h.score).reduce((s1, s2) => min(s1, s2)) >= medianScore === true
      }
    }

    "show own private bookmarks" in {
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 2, numUris = 20)
        val user1 = users(0)
        val user2 = users(1)
        val (privateUris, publicUris) = uris.partition(_.id.get.id % 3 == 0)
        db.readWrite { implicit s =>
          privateUris.foreach{ uri =>
            val url1 = urlRepo.save(urlRepo.get(uri.url).getOrElse(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
            bookmarkRepo.save(BookmarkFactory(title = uri.title.get, url = url1,  uriId = uri.id.get, userId = user1.id.get, source = source))
          }
          publicUris.foreach{ uri =>
            val url1 = urlRepo.save(urlRepo.get(uri.url).getOrElse(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
            bookmarkRepo.save(BookmarkFactory(title = uri.title.get, url = url1,  uriId = uri.id.get, userId = user1.id.get, source = source))
          }
        }

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === 1
        indexer.run() === uris.size

        val mainSearcher = mainSearcherFactory(user1.id.get, Set(user2.id.get), SearchFilter.default(), noBoostConfig)
        val res = mainSearcher.search("alldocs", uris.size, None)

        val publicSet = publicUris.map(u => u.id.get).toSet
        val privateSet = privateUris.map(u => u.id.get).toSet

        res.hits.size === uris.size
      }
    }

    "not show friends private bookmarks" in {
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 2, numUris = 20)
        val user1 = users(0)
        val user2 = users(1)
        val (privateUris, publicUris) = uris.partition(_.id.get.id % 3 == 0)
        db.readWrite { implicit s =>
          privateUris.foreach{ uri =>
            val url = urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
            bookmarkRepo.save(BookmarkFactory(title = uri.title.get, url = url, uriId = uri.id.get, userId = user2.id.get, source = source).withPrivate(true)) // wtf??
          }
          publicUris.foreach{ uri =>
            val url = urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
            bookmarkRepo.save(BookmarkFactory(title = uri.title.get, url = url, uriId = uri.id.get, userId = user2.id.get, source = source))
          }
        }

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === 1
        indexer.run() === uris.size

        val mainSearcher = mainSearcherFactory(user1.id.get, Set(user2.id.get), SearchFilter.default(), noBoostConfig)
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
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        val bookmarks = db.readWrite { implicit s =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              val url1 = urlRepo.save(urlRepo.get(uri.url).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))))
              bookmarkRepo.save(BookmarkFactory(title = "my books", url = url1, uriId = uri.id.get, userId = user.id.get, source = source))
            }
          }
        }

        val store = mkStore(uris)
        val (graph, indexer, mainSearcherFactory) = initIndexes(store)

        graph.update() === users.size
        indexer.run() === uris.size

        val numHitsToReturn = 100
        val userId = users(0).id.get
        val friendIds = users.map(_.id.get).toSet - userId
        val mainSearcher = mainSearcherFactory(userId, friendIds, SearchFilter.default(), noBoostConfig)
        val graphSearcher = mainSearcher.uriGraphSearcher

        var res = mainSearcher.search("document", numHitsToReturn, None)
        res.hits.size > 0 === true

        res = mainSearcher.search("book", numHitsToReturn, None)
        res.hits.size > 0 === true
      }
    }
  }
}
