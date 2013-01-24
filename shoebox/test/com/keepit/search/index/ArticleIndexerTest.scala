package com.keepit.search.index

import com.keepit.scraper.FakeArticleStore
import com.keepit.search.Article
import com.keepit.search.ArticleStore
import com.keepit.search.Lang
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphSearcher
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIStates._
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
import scala.collection.JavaConversions._
import com.keepit.search.MainQueryParser

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

  class Searchable(indexer: ArticleIndexer) {
    def search(queryString: String, percentMatch: Float = 0.0f): Seq[Hit] = {
      val parser = MainQueryParser(Lang("en"), 0.0f, 0.0f)
      parser.setPercentMatch(percentMatch)
      val searcher = indexer.getSearcher
      parser.parseQuery(queryString) match {
        case Some(query) => searcher.search(query)
        case None => Seq.empty[Hit]
      }
    }
  }
  implicit def toSearchable(indexer: ArticleIndexer) = new Searchable(indexer)

  "ArticleIndexer" should {
    "index scraped URIs" in {
      running(new EmptyApplication()) {
        var (uri1, uri2, uri3) = CX.withConnection { implicit c =>
          val user1 = User(firstName = "Joe", lastName = "Smith").save
          val user2 = User(firstName = "Moo", lastName = "Brown").save
          (NormalizedURIFactory(title = "a1", url = "http://www.keepit.com/article1", state = ACTIVE).save,
           NormalizedURIFactory(title = "a2", url = "http://www.keepit.org/article2", state = SCRAPED).save,
           NormalizedURIFactory(title = "a3", url = "http://www.findit.com/article3", state = INDEXED).save)
        }
        store += (uri1.id.get -> mkArticle(uri1.id.get, "title1 titles", "content1 alldocs body soul"))
        store += (uri2.id.get -> mkArticle(uri2.id.get, "title2 titles", "content2 alldocs bodies soul"))
        store += (uri3.id.get -> mkArticle(uri3.id.get, "title3 titles", "content3 alldocs bodies souls"))

        // saving ids for the search test
        uriIdArray(0) = uri1.id.get.id
        uriIdArray(1) = uri2.id.get.id
        uriIdArray(2) = uri3.id.get.id

        val indexer = ArticleIndexer(ramDir, store)
        indexer.run

        indexer.numDocs === 1

        CX.withConnection { implicit c =>
          uri1 = NormalizedURICxRepo.get(uri1.id.get)
          uri2 = NormalizedURICxRepo.get(uri2.id.get)
          uri3 = NormalizedURICxRepo.get(uri3.id.get)
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
          uri1 = NormalizedURICxRepo.get(uri1.id.get)
          uri2 = NormalizedURICxRepo.get(uri2.id.get)
          uri3 = NormalizedURICxRepo.get(uri3.id.get)
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

    "search documents using stemming" in {
      val indexer = ArticleIndexer(ramDir, store)

      indexer.search("alldoc").size === 3
      indexer.search("title").size === 3
      indexer.search("bodies").size === 3
      indexer.search("soul").size === 3
      indexer.search("+bodies +souls").size === 3
      indexer.search("+body +soul").size === 3
    }

    "limit the result by percentMatch" in {
      val indexer = ArticleIndexer(ramDir, store)

      var res = indexer.search("title1 alldocs", percentMatch = 0.0f)
      res.size === 3

      res = indexer.search("title1 alldocs", percentMatch = 50.0f)
      res.size === 3

      res = indexer.search("title1 alldocs", percentMatch = 60.0f)
      res.size === 1

      res = indexer.search("title1 title2 alldocs", percentMatch = 60.0f)
      res.size === 2

      res = indexer.search("title1 title2 alldocs", percentMatch = 75.0f)
      res.size === 0
    }

    "limit the result by site" in {
      val indexer = ArticleIndexer(ramDir, store)

      var res = indexer.search("alldocs")
      res.size === 3

      res = indexer.search("alldocs site:com")
      res.size === 2

      res = indexer.search("alldocs site:org")
      res.size === 1

      res = indexer.search("alldocs site:keepit.com")
      res.size === 1

      res = indexer.search("site:com")
      res.size === 2

      res = indexer.search("site:keepit.com")
      res.size === 1

      res = indexer.search("alldocs site:com -site:keepit.org")
      res.size === 2

      res = indexer.search("alldocs site:com -site:keepit.com")
      res.size === 1

      res = indexer.search("alldocs -site:keepit.org")
      res.size === 2
    }

    "be able to dump Lucene Document" in {
      running(new EmptyApplication()) {
        val ramDir = new RAMDirectory
        val store = new FakeArticleStore()

        var uri = CX.withConnection { implicit c =>
          NormalizedURIFactory(title = "a1", url = "http://www.keepit.com/article1", state = ACTIVE).save
        }
        store += (uri.id.get -> mkArticle(uri.id.get, "title1 titles", "content1 alldocs body soul"))

        val indexer = ArticleIndexer(ramDir, store)
        val doc = indexer.buildIndexable(uri.id.get).buildDocument
        doc.getFields.forall{ f => indexer.getFieldDecoder(f.name).apply(f).length > 0 } === true
      }
    }
  }
}
