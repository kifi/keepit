package com.keepit.search

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphSearcher
import com.keepit.search.index.ArticleIndexer
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._
import com.keepit.common.db.{Id, CX}
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

@RunWith(classOf[JUnitRunner])
class MainSearcherTest extends SpecificationWithJUnit {

  def mkArticle(normalizedUriId: Id[NormalizedURI], title: String, content: String) = {
    Article(
        id = normalizedUriId,
        title = title,
        content = content,
        scrapedAt = currentDateTime,
        httpContentType = Some("text/html"),
        state = SCRAPED,
        message = None)
  }
  
  "MainSearcher" should {
    "search and categorize using social graph" in {
      running(new EmptyApplication()) {
        val (users, uris) = CX.withConnection { implicit c =>
          ((0 to 9).map(n => User(firstName = "foo" + n, lastName = "").save).toList,
           (0 to 9).map(n => NormalizedURI(title = "a" + n, url = "http://www.keepit.com/article" + n, state=SCRAPED).save).toList)
        }
        val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        val bookmarks = CX.withConnection { implicit c =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              Bookmark(title = uri.title, url = uri.url,  uriId = uri.id.get, userId = user.id.get, source = BookmarkSource("test")).save
            }
          }
        }

        val store = {
          uris.zipWithIndex.foldLeft(new FakeArticleStore){ case (store, (uri, idx)) =>
            store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), "content%d alldocs".format(idx)))
            store
          }
        }
        
        val graphDir = new RAMDirectory
        val graph = URIGraph(graphDir)
        val indexDir = new RAMDirectory
        val indexer = ArticleIndexer(indexDir, store)
        
        graph.load() === users.size
        indexer.run() === uris.size
        
        val config = SearchConfig.getDefaultConfig
        val mainSearcher= new MainSearcher(indexer, graph, config)
        val graphSearcher = mainSearcher.uriGraphSearcher
        
        val numHitsPerCategory = 1000
        users.foreach{ user =>
           val userId = user.id.get
           users.sliding(3).foreach{ friends =>
            val friendIds = friends.map(_.id.get).toSet - userId
            val (myHits, friendsHits, othersHits) = mainSearcher.searchText("alldocs", userId, friendIds, Set.empty[Long], numHitsPerCategory)
            
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
      
      "return a single list of hits" in {
        running(new EmptyApplication()) {
          val (users, uris) = CX.withConnection { implicit c =>
            ((0 to 9).map(n => User(firstName = "foo" + n, lastName = "").save).toList,
             (0 to 9).map(n => NormalizedURI(title = "a" + n, url = "http://www.keepit.com/article" + n, state=SCRAPED).save).toList)
          }
          val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
          val bookmarks = CX.withConnection { implicit c =>
            expectedUriToUserEdges.flatMap{ case (uri, users) =>
              users.map{ user =>
                Bookmark(title = uri.title, url = uri.url,  uriId = uri.id.get, userId = user.id.get, source = BookmarkSource("test")).save
              }
            }
          }

          val store = {
            uris.zipWithIndex.foldLeft(new FakeArticleStore){ case (store, (uri, idx)) =>
              store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), "content%d alldocs".format(idx)))
              store
            }
          }
          
          val graphDir = new RAMDirectory
          val graph = URIGraph(graphDir)
          val indexDir = new RAMDirectory
          val indexer = ArticleIndexer(indexDir, store)
          
          graph.load() === users.size
          indexer.run() === uris.size
          
          val config = SearchConfig.getDefaultConfig
          val mainSearcher= new MainSearcher(indexer, graph, config)
          val graphSearcher = mainSearcher.uriGraphSearcher
          
          val numHitsToReturn = 7
          users.foreach{ user =>
            val userId = user.id.get
            //println("user:" + userId)
            users.sliding(3).foreach{ friends =>
              val friendIds = friends.map(_.id.get).toSet - userId

              val myUriIds = graphSearcher.getUserToUriEdgeSet(userId).destIdSet
              val friendsUriIds = friends.foldLeft(Set.empty[Id[NormalizedURI]]){ (s, f) => 
                s ++ graphSearcher.getUserToUriEdgeSet(f.id.get).destIdSet
              } -- myUriIds
              val othersUriIds = (uris.map(_.id.get).toSet) -- friendsUriIds -- myUriIds

              val hits = mainSearcher.search("alldocs", userId, friendIds, Set.empty[Long], numHitsToReturn)
              
              var mCnt = 0
              var fCnt = 0
              var oCnt = 0
              hits.foreach{ h => 
                if (h.isMyBookmark) mCnt += 1
                else if (! h.users.isEmpty) fCnt += 1
                else oCnt += 1
              }
              //println(hits)
              //println(List(mCnt, fCnt, oCnt))
              mCnt === min(myUriIds.size, config.asInt("maxMyBookmarks"))
              fCnt === min(friendsUriIds.size, numHitsToReturn - mCnt)
              oCnt === (hits.size - mCnt - fCnt)
            }
          }
          indexer.numDocs === uris.size
        }
      }
      
      "paginate" in {
        running(new EmptyApplication()) {
          val (users, uris) = CX.withConnection { implicit c =>
            ((0 to 9).map(n => User(firstName = "foo" + n, lastName = "").save).toList,
             (0 to 9).map(n => NormalizedURI(title = "a" + n, url = "http://www.keepit.com/article" + n, state=SCRAPED).save).toList)
          }
          val expectedUriToUserEdges = uris.toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
          val bookmarks = CX.withConnection { implicit c =>
            expectedUriToUserEdges.flatMap{ case (uri, users) =>
              users.map{ user =>
                Bookmark(title = uri.title, url = uri.url,  uriId = uri.id.get, userId = user.id.get, source = BookmarkSource("test")).save
              }
            }
          }

          val store = {
            uris.zipWithIndex.foldLeft(new FakeArticleStore){ case (store, (uri, idx)) =>
              store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), "content%d alldocs".format(idx)))
              store
            }
          }
          
          val graphDir = new RAMDirectory
          val graph = URIGraph(graphDir)
          val indexDir = new RAMDirectory
          val indexer = ArticleIndexer(indexDir, store)
          
          graph.load() === users.size
          indexer.run() === uris.size
          
          val config = SearchConfig.getDefaultConfig
          val mainSearcher= new MainSearcher(indexer, graph, config)
          val graphSearcher = mainSearcher.uriGraphSearcher
          
          val numHitsToReturn = 3
          val userId = Id[User](8)
          
          val friendIds = Set(Id[User](6))
          var uriSeen = Set.empty[Long]
          while(uriSeen.size < uris.size) {
            println("---")
            val hits = mainSearcher.search("alldocs", userId, friendIds, uriSeen, numHitsToReturn)
            hits.foreach{ h => 
              println(h)
              uriSeen.contains(h.uriId.id) === false
              uriSeen += h.uriId.id
            }
          }
          indexer.numDocs === uris.size
        }
      }
    }
  }
}