package com.keepit.search.index

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphSearcher
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
import com.keepit.search.graph.UserToUserEdgeSet
import scala.math._

@RunWith(classOf[JUnitRunner])
class ArticleIndexerTest extends SpecificationWithJUnit {

  val ramDir = new RAMDirectory
  val store = new FakeArticleStore()
  val uriIdArray = new Array[Long](3)

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
  
  def getFakeURIGraph = new URIGraph {
    def load(): Int = throw new UnsupportedOperationException
    def update(id:Id[User]): Int = throw new UnsupportedOperationException
    def getURIGraphSearcher: URIGraphSearcher  = throw new UnsupportedOperationException
  }
  
  "ArticleIndexer" should {
    "index scraped URIs" in {
      running(new EmptyApplication()) {
        var (uri1, uri2, uri3) = CX.withConnection { implicit c =>
          val user1 = User(firstName = "Joe", lastName = "Smith").save
          val user2 = User(firstName = "Moo", lastName = "Brown").save
          (NormalizedURI(title = "a1", url = "http://www.keepit.com/article1", state = ACTIVE).save,
           NormalizedURI(title = "a2", url = "http://www.keepit.com/article2", state = SCRAPED).save,
           NormalizedURI(title = "a3", url = "http://www.keepit.com/article3", state = INDEXED).save)
        }
        store += (uri1.id.get -> mkArticle(uri1.id.get, "title1", "content1 alldocs"))
        store += (uri2.id.get -> mkArticle(uri2.id.get, "title2", "content2 alldocs"))
        store += (uri3.id.get -> mkArticle(uri3.id.get, "title3", "content3 alldocs"))

        // saving ids for the search test
        uriIdArray(0) = uri1.id.get.id
        uriIdArray(1) = uri2.id.get.id
        uriIdArray(2) = uri3.id.get.id
        
        val indexer = ArticleIndexer(ramDir, store, getFakeURIGraph)
        indexer.run
        
        indexer.numDocs === 1
        
        CX.withConnection { implicit c =>
          uri1 = NormalizedURI.get(uri1.id.get) 
          uri2 = NormalizedURI.get(uri2.id.get)
          uri3 = NormalizedURI.get(uri3.id.get)
        }
        uri1.state === ACTIVE 
        uri2.state === INDEXED
        uri3.state === INDEXED
      
        CX.withConnection { implicit c =>
          uri1 = uri1.withState(SCRAPED).save
          uri2 = uri2.withState(SCRAPED).save
          uri3 = uri3.withState(SCRAPED).save
        }
        
        indexer.run
        
        indexer.numDocs === 3
        
        CX.withConnection { implicit c =>
          uri1 = NormalizedURI.get(uri1.id.get) 
          uri2 = NormalizedURI.get(uri2.id.get)
          uri3 = NormalizedURI.get(uri3.id.get)
        }
        uri1.state === INDEXED 
        uri2.state === INDEXED
        uri3.state === INDEXED
      }
    }
    
    "search documents (hits in contents)" in {
      val indexer = ArticleIndexer(ramDir, store, getFakeURIGraph)
      
      indexer.search("alldocs").size === 3
      
      var res = indexer.search("content1")
      res.size === 1
      res.head.id === uriIdArray(0)
      
      res = indexer.search("content2")
      res.size === 1
      res.head.id === uriIdArray(1)
      
      res = indexer.search("content3")
      res.size === 1
      res.head.id === uriIdArray(2)
    }
    
    "search documents (hits in titles)" in {
      val indexer = ArticleIndexer(ramDir, store, getFakeURIGraph)
      
      var res = indexer.search("title1")
      res.size === 1
      res.head.id === uriIdArray(0)
      
      res = indexer.search("title2")
      res.size === 1
      res.head.id === uriIdArray(1)
      
      res = indexer.search("title3")
      res.size === 1
      res.head.id === uriIdArray(2)
    }
    
    "search documents (hits in contents and titles)" in {
      val indexer = ArticleIndexer(ramDir, store, getFakeURIGraph)
      
      var res = indexer.search("title1 alldocs")
      res.size === 3
      res.head.id === uriIdArray(0)
      
      res = indexer.search("title2 alldocs")
      res.size === 3
      res.head.id === uriIdArray(1)
      
      res = indexer.search("title3 alldocs")
      res.size === 3
      res.head.id === uriIdArray(2)
    }
    
    "search and categorize using social graph" in {
      running(new EmptyApplication()) {
        val (users, uris) = CX.withConnection { implicit c =>
          ((0 to 9).map(n => User(firstName = "foo" + n, lastName = "").save).toList,
           (0 to 9).map(n => NormalizedURI(title = "a" + n, url = "http://www.keepit.com/article" + n, state=SCRAPED).save).toList)
        }
        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList
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
        val indexer = ArticleIndexer(indexDir, store, graph)
        
        graph.load() === users.size
        indexer.run() === uris.size
        
        val graphSearcher = graph.getURIGraphSearcher()
        
        val maxMine = 4
        val maxFriends = 3
        val maxOthers = 2
        
        users.foreach{ user =>
           val userId = user.id.get
           users.sliding(3).foreach{ friends =>
            val friendIds = friends.map(_.id.get).toSet - userId
            val userToUserEdgeSet = new UserToUserEdgeSet(userId, friendIds)
            val res = indexer.search("alldocs", userId, friendIds, maxMine, maxFriends, maxOthers)
            
            //println("----")
            val myUriIds = graphSearcher.getUserToUriEdgeSet(userId).destIdSet
            res.myHits.size === min(myUriIds.size, maxMine)
            res.myHits.foreach{ h =>
              //println("users:" + h)
              (myUriIds contains h.uriId) === true 
              (h.friends subsetOf friendIds) == true
            }
            
            val friendsUriIds = friends.foldLeft(Set.empty[Id[NormalizedURI]]){ (s, f) => 
              s ++ graphSearcher.getUserToUriEdgeSet(f.id.get).destIdSet
            } -- myUriIds
            res.friendsHits.size === min(friendsUriIds.size, maxFriends)
            res.friendsHits.foreach{ h =>
              //println("friends:"+ h)
              (myUriIds contains h.uriId) === false
              (friendsUriIds contains h.uriId) === true
              (h.friends subsetOf friendIds) == true
            }
            
            val othersUriIds = (uris.map(_.id.get).toSet) -- friendsUriIds -- myUriIds
            res.othersHits.size === min(othersUriIds.size, maxOthers)
            res.othersHits.foreach{ h =>
              //println("others:"+ h)
              (myUriIds contains h.uriId) === false
              (friendsUriIds contains h.uriId) === false
              h.friends.isEmpty == true
            }
          }
        }
        indexer.numDocs === uris.size
      }
    }
  }
}