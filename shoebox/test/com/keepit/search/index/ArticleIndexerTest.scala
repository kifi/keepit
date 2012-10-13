package com.keepit.search.index

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURI.States._
import com.keepit.common.db.CX
import com.keepit.common.db.Id
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
class ArticleIndexerTest extends SpecificationWithJUnit {

  val ramDir = new RAMDirectory
  
  "ArticleIndexer" should {
    "index scraped URIs" in {
      running(new EmptyApplication()) {
        var (uri1, uri2, uri3) = CX.withConnection { implicit c =>
          val user1 = User(firstName = "Joe", lastName = "Smith").save
          val user2 = User(firstName = "Moo", lastName = "Brown").save
          (NormalizedURI(title = "a1", url = "http://www.keepit.com/article1", state=ACTIVE).save,
           NormalizedURI(title = "a2", url = "http://www.keepit.com/article2", state=SCRAPED).save,
           NormalizedURI(title = "a3", url = "http://www.keepit.com/article3", state=INDEXED).save)
        }
        val store = new FakeArticleStore()
        store += (uri1.id.get -> Article(uri1.id.get, "title1", "content1 all"))
        store += (uri2.id.get -> Article(uri2.id.get, "title2", "content2 all"))
        store += (uri3.id.get -> Article(uri3.id.get, "title3", "content3 all"))

        val indexer = ArticleIndexer(ramDir, store)
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
  }
}
