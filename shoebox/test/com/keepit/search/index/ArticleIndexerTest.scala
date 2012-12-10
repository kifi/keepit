package com.keepit.search.index

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
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
        httpOriginalContentCharset = Option("UTF-8"),
        state = SCRAPED,
        message = None,
        titleLang = Some(Lang("en")),
        contentLang = Some(Lang("en")))
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

    "search documents (hits in contents)" in {
      val indexer = ArticleIndexer(ramDir, store)

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
      val indexer = ArticleIndexer(ramDir, store)

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
      val indexer = ArticleIndexer(ramDir, store)

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

    "limit the result by percentMatch" in {
      val indexer = ArticleIndexer(ramDir, store)

      val parser = indexer.getQueryParser(Lang("en"))

      var res = indexer.search("title1 alldocs")
      res.size === 3

      parser.setPercentMatch(50)
      res = indexer.getArticleSearcher.search(parser.parseQuery("title1 alldocs").get)
      res.size === 3

      parser.setPercentMatch(60)
      res = indexer.getArticleSearcher.search(parser.parseQuery("title1 alldocs").get)
      res.size === 1

      parser.setPercentMatch(60)
      res = indexer.getArticleSearcher.search(parser.parseQuery("title1 title2 alldocs").get)
      res.size === 2

      parser.setPercentMatch(75)
      res = indexer.getArticleSearcher.search(parser.parseQuery("title1 title2 alldocs").get)
      res.size === 0
    }
  }
}
