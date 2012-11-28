package com.keepit.search

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphSearcher
import com.keepit.search.graph.URIList
import com.keepit.search.index.ArticleIndexer
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._
import com.keepit.common.db.{Id, CX, ExternalId}
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.test.EmptyApplication
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import org.apache.lucene.store.RAMDirectory
import scala.math._
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class MainSearcherTest extends SpecificationWithJUnit {

  def initData(numUsers: Int, numUris: Int) = CX.withConnection { implicit c =>
    ((0 until numUsers).map(n => User(firstName = "foo" + n, lastName = "").save).toList,
     (0 until numUris).map(n => NormalizedURI(title = "a" + n, url = "http://www.keepit.com/article" + n, state=SCRAPED).save).toList)
  }

  def initIndexes(store: ArticleStore) = {
    val graphDir = new RAMDirectory
    val indexDir = new RAMDirectory
    (URIGraph(graphDir), ArticleIndexer(indexDir, store))
  }

  def mkStore(uris: Seq[NormalizedURI]) = {
    uris.zipWithIndex.foldLeft(new FakeArticleStore){ case (store, (uri, idx)) =>
      store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), "content%d alldocs".format(idx)))
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
        message = None)
  }

  val source = BookmarkSource("test")

  "MainSearcher" should {
    "search and categorize using social graph" in {
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        val bookmarks = CX.withConnection { implicit c =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              Bookmark(title = uri.title, url = uri.url,  uriId = uri.id.get, userId = user.id.get, source = source).save
            }
          }
        }

        val store = mkStore(uris)
        val (graph, indexer) = initIndexes(store)

        graph.load() === users.size
        indexer.run() === uris.size

        val config = SearchConfig(Map("tailCutting" -> "0"))

        val numHitsPerCategory = 1000
        users.foreach{ user =>
          users.sliding(3).foreach{ friends =>
            val userId = user.id.get
            val friendIds = friends.map(_.id.get).toSet - userId
            val mainSearcher= new MainSearcher(userId, friendIds, Set.empty[Long], indexer, graph, config)
            val graphSearcher = mainSearcher.uriGraphSearcher
            val (myHits, friendsHits, othersHits) = mainSearcher.searchText("alldocs", numHitsPerCategory)

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
        val bookmarks = CX.withConnection { implicit c =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              Bookmark(title = uri.title, url = uri.url,  uriId = uri.id.get, userId = user.id.get, source = source).save
            }
          }
        }

        val store = mkStore(uris)
        val (graph, indexer) = initIndexes(store)

        graph.load() === users.size
        indexer.run() === uris.size

        val config = SearchConfig(Map("tailCutting" -> "0"))

        val numHitsToReturn = 7
        users.foreach{ user =>
          val userId = user.id.get
          //println("user:" + userId)
          users.sliding(3).foreach{ friends =>
            val friendIds = friends.map(_.id.get).toSet - userId

            val mainSearcher= new MainSearcher(userId, friendIds, Set.empty[Long], indexer, graph, config)
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
            (mCnt >= min(myUriIds.size, config.asInt("minMyBookmarks"))) === true
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
        val bookmarks = CX.withConnection { implicit c =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              Bookmark(title = "personaltitle", url = uri.url,  uriId = uri.id.get, userId = user.id.get, source = source).save
            }
          }
        }

        val store = mkStore(uris)
        val (graph, indexer) = initIndexes(store)

        graph.load() === users.size
        indexer.run() === uris.size

        val config = SearchConfig(Map("tailCutting" -> "0"))

        val numHitsToReturn = 100
        users.foreach{ user =>
          val userId = user.id.get
          //println("user:" + userId)
          val friendIds = users.map(_.id.get).toSet - userId
          val mainSearcher= new MainSearcher(userId, friendIds, Set.empty[Long], indexer, graph, config)
          val graphSearcher = mainSearcher.uriGraphSearcher
          val res = mainSearcher.search("personaltitle", numHitsToReturn, None)

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
        indexer.numDocs === uris.size
      }
    }

    "blend a bookmark title score and an article score" in {
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        val bookmarks = CX.withConnection { implicit c =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              Bookmark(title = "personaltitle", url = uri.url,  uriId = uri.id.get, userId = user.id.get, source = source).save
            }
          }
        }

        val store = mkStore(uris)
        val (graph, indexer) = initIndexes(store)

        graph.load() === users.size
        indexer.run() === uris.size

        val config = SearchConfig(Map("tailCutting" -> "0", "percentMatch" -> "0", "proximityBoost" -> "0", "semanticBoost" -> "0"))

        val numHitsToReturn = 100
        val userId = users(0).id.get
        //println("user:" + userId)
        val friendIds = users.map(_.id.get).toSet - userId
        val mainSearcher= new MainSearcher(userId, friendIds, Set.empty[Long], indexer, graph, config)
        val graphSearcher = mainSearcher.uriGraphSearcher

        val expected = (uris(3) :: ((uris diff List(uris(3))).reverse)).map(_.id.get).toList
        val res = mainSearcher.search("personaltitle title3 content3", numHitsToReturn, None)

        val myUriIds = graphSearcher.getUserToUriEdgeSet(userId).destIdSet

        println(res.hits)
        res.hits.map(h => h.uriId).toList === expected
      }
    }

    "paginate" in {
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        val bookmarks = CX.withConnection { implicit c =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              Bookmark(title = uri.title, url = uri.url,  uriId = uri.id.get, userId = user.id.get, source = source).save
            }
          }
        }

        val store = mkStore(uris)
        val (graph, indexer) = initIndexes(store)

        graph.load() === users.size
        indexer.run() === uris.size

        val config = SearchConfig(Map("tailCutting" -> "0"))

        val numHitsToReturn = 3
        val userId = Id[User](8)

        val friendIds = Set(Id[User](6))
        var uriSeen = Set.empty[Long]

        val mainSearcher= new MainSearcher(userId, friendIds, uriSeen, indexer, graph, config)
        val graphSearcher = mainSearcher.uriGraphSearcher
        val reachableUris = users.foldLeft(Set.empty[Long])((s, u) => s ++ graphSearcher.getUserToUriEdgeSet(u.id.get, publicOnly = true).destIdLongSet)

        var uuid : Option[ExternalId[ArticleSearchResultRef]] = None
        while(uriSeen.size < reachableUris.size) {
          val mainSearcher= new MainSearcher(userId, friendIds, uriSeen, indexer, graph, config)
          //println("---" + uriSeen + ":" + reachableUris)
          val res = mainSearcher.search("alldocs", numHitsToReturn, uuid)
          res.hits.foreach{ h =>
            //println(h)
            uriSeen.contains(h.uriId.id) === false
            uriSeen += h.uriId.id
          }
          uuid = Some(res.uuid)
        }
        indexer.numDocs === uris.size
      }
    }

    "boost recent bookmarks" in {
      running(new EmptyApplication()) {
        val (users, uris) = initData(numUsers = 1, numUris = 20)
        val userId = users.head.id.get
        val now = currentDateTime
        val rand = new Random

        val bookmarkMap = CX.withConnection { implicit c =>
          uris.foldLeft(Map.empty[Id[NormalizedURI], Bookmark]){ (m, uri) =>
            val createdAt = now.minusHours(rand.nextInt(100))
            val uriId = uri.id.get
            m + (uriId -> Bookmark(createdAt = createdAt, title = uri.title, url = uri.url,  uriId = uriId, userId = userId, source = source).save)
          }
        }

        val store = mkStore(uris)
        val (graph, indexer) = initIndexes(store)

        graph.load() === 1
        indexer.run() === uris.size

        val config = SearchConfig(Map("tailCutting" -> "0", "proximityBoost" -> "0", "semanticBoost" -> "0"))

        val mainSearcher= new MainSearcher(userId, Set.empty[Id[User]], Set.empty[Long], indexer, graph, config)
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

        val bookmarks = CX.withConnection { implicit c =>
          uris.map{ uri => Bookmark(title = uri.title, url = uri.url,  uriId = uri.id.get, userId = userId, source = source).save }
        }

        val store = {
         val sz = uris.size
          uris.zipWithIndex.foldLeft(new FakeArticleStore){ case (store, (uri, idx)) =>
            store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), "alldocs " * idx + "dummy" * (sz - idx)))
            store
          }
        }
        val (graph, indexer) = initIndexes(store)

        graph.load() === 1
        indexer.run() === uris.size

        var config = SearchConfig(Map("myBookmarkBoost" -> "1", "sharingBoost" -> "0", "recencyBoost" -> "0", "proximityBoost" -> "0", "semanticBoost" -> "0",
                                      "tailCutting" -> "0"))
        var mainSearcher= new MainSearcher(userId, Set.empty[Id[User]], Set.empty[Long], indexer, graph, config)
        var res = mainSearcher.search("alldocs", uris.size, None)
        //println("Scores: " + res.hits.map(_.score))
        val sz = res.hits.size
        val maxScore = res.hits.head.score
        val minScore = res.hits(sz - 1).score
        val medianScore = res.hits(sz/2).score
        (minScore < medianScore && medianScore < maxScore) === true // this is a sanity check of test data

        config = SearchConfig(Map("myBookmarkBoost" -> "1", "sharingBoost" -> "0", "recencyBoost" -> "0", "proximityBoost" -> "0", "semanticBoost" -> "0",
                                  "tailCutting" -> medianScore.toString))
        mainSearcher= new MainSearcher(userId, Set.empty[Id[User]], Set.empty[Long], indexer, graph, config)
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
        CX.withConnection { implicit c =>
          privateUris.foreach{ uri =>
            Bookmark(title = uri.title, url = uri.url, uriId = uri.id.get, userId = user1.id.get, source = source, isPrivate = true).save
          }
          publicUris.foreach{ uri =>
            Bookmark(title = uri.title, url = uri.url, uriId = uri.id.get, userId = user1.id.get, source = source, isPrivate = false).save
          }
        }

        val store = mkStore(uris)
        val (graph, indexer) = initIndexes(store)

        graph.load() === users.size
        indexer.run() === uris.size

        var mainSearcher= new MainSearcher(user1.id.get, Set(user2.id.get), Set.empty[Long], indexer, graph, SearchConfig.getDefaultConfig)
        var res = mainSearcher.search("alldocs", uris.size, None)

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
        CX.withConnection { implicit c =>
          privateUris.foreach{ uri =>
            Bookmark(title = uri.title, url = uri.url, uriId = uri.id.get, userId = user2.id.get, source = source, isPrivate = true).save
          }
          publicUris.foreach{ uri =>
            Bookmark(title = uri.title, url = uri.url, uriId = uri.id.get, userId = user2.id.get, source = source, isPrivate = false).save
          }
        }

        val store = mkStore(uris)
        val (graph, indexer) = initIndexes(store)

        graph.load() === users.size
        indexer.run() === uris.size

        var mainSearcher= new MainSearcher(user1.id.get, Set(user2.id.get), Set.empty[Long], indexer, graph, SearchConfig.getDefaultConfig)
        var res = mainSearcher.search("alldocs", uris.size, None)

        val publicSet = publicUris.map(u => u.id.get).toSet
        val privateSet = privateUris.map(u => u.id.get).toSet

        res.hits.foreach{ h =>
          publicSet.contains(h.uriId) === true
          privateSet.contains(h.uriId) === false
        }
        res.hits.size === publicUris.size
      }
    }
  }
}
