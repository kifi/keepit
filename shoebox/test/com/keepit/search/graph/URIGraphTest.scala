package com.keepit.search.graph

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.Article
import com.keepit.search.ArticleStore
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

@RunWith(classOf[JUnitRunner])
class URIGraphTest extends SpecificationWithJUnit {

  private def setupDB = {
    CX.withConnection { implicit c =>
      (List(User(firstName = "Agrajag", lastName = "", facebookId = FacebookId("fbA")).save,
            User(firstName = "Barmen", lastName = "", facebookId = FacebookId("fbB")).save,
            User(firstName = "Colin", lastName = "", facebookId = FacebookId("fbC")).save,
            User(firstName = "Dan", lastName = "", facebookId = FacebookId("fbD")).save,
            User(firstName = "Eccentrica", lastName = "", facebookId = FacebookId("fbE")).save,
            User(firstName = "Hactar", lastName = "", facebookId = FacebookId("fbF")).save),
       List(NormalizedURI(title = "a1", url = "http://www.keepit.com/article1", state=SCRAPED).save,
            NormalizedURI(title = "a2", url = "http://www.keepit.com/article2", state=SCRAPED).save,
            NormalizedURI(title = "a3", url = "http://www.keepit.com/article3", state=SCRAPED).save,
            NormalizedURI(title = "a4", url = "http://www.keepit.com/article4", state=SCRAPED).save,
            NormalizedURI(title = "a5", url = "http://www.keepit.com/article5", state=SCRAPED).save,
            NormalizedURI(title = "a6", url = "http://www.keepit.com/article6", state=SCRAPED).save))
    }
  }
  
  private def setupArticleStore(uris: Seq[NormalizedURI]) = {
    uris.zipWithIndex.foldLeft(new FakeArticleStore){ case (store, (uri, idx)) =>
      store += (uri.id.get -> mkArticle(uri.id.get, "title%d".format(idx), "content%d alldocs".format(idx)))
      store
    }
  }
  
  private def mkArticle(normalizedUriId: Id[NormalizedURI], title: String, content: String) = {
    Article(
        id = normalizedUriId,
        title = title,
        content = content,
        scrapedAt = currentDateTime,
        httpContentType = Some("text/html"),
        state = SCRAPED,
        message = None)
  }
  
  "URIGraph" should {
    "be able to generate UriToUsrEdgeSet" in {
      running(new EmptyApplication()) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)
        
        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList

        val bookmarks = CX.withConnection { implicit c =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              Bookmark(title = uri.title, url = uri.url,  uriId = uri.id.get, userId = user.id, source = BookmarkSource("test")).save
            }
          }
        }
        
        val graphDir = new RAMDirectory
        val graph = URIGraph(graphDir)
        graph.load() === users.size        
        
        val searcher = graph.getURIGraphSearcher()
        
        expectedUriToUserEdges.map{ case (uri, users) =>
          val expected = users.map(_.id.get).toSet
          val answer = searcher.getUriToUserEdgeSet(uri.id.get).destIdSet
          answer === expected
        }
        
        graph.numDocs === users.size
      }
    }
    
    "be able to generate UserToUriEdgeSet" in {
      running(new EmptyApplication()) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)
        
        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList

        val bookmarks = CX.withConnection { implicit c =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              Bookmark(title = uri.title, url = uri.url,  uriId = uri.id.get, userId = user.id, source = BookmarkSource("test")).save
            }
          }
        }
        
        val graphDir = new RAMDirectory
        val graph = URIGraph(graphDir)
        graph.load() === users.size        
        
        val searcher = graph.getURIGraphSearcher()
        
        val expectedUserIdToUriIdEdges = bookmarks.groupBy(_.userId.get).map{ case (userId, bookmarks) => (userId, bookmarks.map(_.uriId)) }
        expectedUserIdToUriIdEdges.map{ case (userId, uriIds) =>
          val expected = uriIds.toSet
          val answer = searcher.getUserToUriEdgeSet(userId).destIdSet
          answer === expected
        }
        
        graph.numDocs === users.size
      }
    }
    
    "be able to intersect UserToUserEdgeSet and UriToUserEdgeSet" in {
      running(new EmptyApplication()) {
        val (users, uris) = setupDB
        val store = setupArticleStore(uris)
        
        val expectedUriToUserEdges = uris.toIterator.zip(users.sliding(4) ++ users.sliding(3)).toList

        val bookmarks = CX.withConnection { implicit c =>
          expectedUriToUserEdges.flatMap{ case (uri, users) =>
            users.map{ user =>
              Bookmark(title = uri.title, url = uri.url,  uriId = uri.id.get, userId = user.id, source = BookmarkSource("test")).save
            }
          }
        }
        
        val graphDir = new RAMDirectory
        val graph = URIGraph(graphDir)
        graph.load() === users.size        
        
        val searcher = graph.getURIGraphSearcher()
        
        users.sliding(3).foreach{ friends =>
          val friendIds = friends.map(_.id.get).toSet
          val userToUserEdgeSet = new UserToUserEdgeSet(Id[User](1000), friendIds)

          expectedUriToUserEdges.map{ case (uri, users) =>
            val expected = (users.map(_.id.get).toSet intersect friendIds)
            val answer = searcher.intersect(userToUserEdgeSet, searcher.getUriToUserEdgeSet(uri.id.get)).destIdSet
            //println("friends:"+ friendIds)
            //println("users:" + users.map(_.id.get))
            //println("expected:" + expected)
            //println("answer:" + answer)
            //println("---")
            answer === expected
          }
        }
        
        graph.numDocs === users.size
      }
      
      "handle empty sets" in {
        running(new EmptyApplication()) {
          val (users, uris) = setupDB
          
          val graphDir = new RAMDirectory
          val graph = URIGraph(graphDir)
          val searcher = graph.getURIGraphSearcher()
        
          searcher.getUserToUriEdgeSet(Id[User](10000)).destIdSet.isEmpty === true
          
          searcher.getUriToUserEdgeSet(Id[NormalizedURI](10000)).destIdSet.isEmpty === true
          
          val emptyUserToUserEdgeSet = new UserToUserEdgeSet(Id[User](10000), Set())
          
          emptyUserToUserEdgeSet.destIdSet.isEmpty === true
          
          searcher.intersect(emptyUserToUserEdgeSet, searcher.getUriToUserEdgeSet(uris.head.id.get)).destIdSet.isEmpty === true
        
          searcher.intersect(emptyUserToUserEdgeSet, searcher.getUriToUserEdgeSet(Id[NormalizedURI](10000))).destIdSet.isEmpty === true
          
          val userToUserEdgeSet = new UserToUserEdgeSet(Id[User](10000), users.map(_.id.get).toSet)
          searcher.intersect(userToUserEdgeSet, searcher.getUriToUserEdgeSet(Id[NormalizedURI](10000))).destIdSet.isEmpty === true
        }
      }
    }
  }
}
