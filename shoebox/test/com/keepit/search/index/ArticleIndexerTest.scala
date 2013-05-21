package com.keepit.search.index

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.NormalizedURIStates._
import com.keepit.model._
import com.keepit.scraper.FakeArticleStore
import com.keepit.search.Article
import com.keepit.search.Lang
import com.keepit.search.MainQueryParserFactory
import com.keepit.search.phrasedetector._
import com.keepit.test._
import com.keepit.inject._
import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import org.apache.lucene.store.RAMDirectory
import org.specs2.specification.Scope
import play.api.test.Helpers._
import scala.collection.JavaConversions._
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.shoebox.ShoeboxServiceClient

class ArticleIndexerTest extends Specification with DbRepos {

  private trait IndexerScope extends Scope {
    val ramDir = new RAMDirectory
    val store = new FakeArticleStore()
    val uriIdArray = new Array[Long](3)
    val parserFactory = new MainQueryParserFactory(new PhraseDetector(new FakePhraseIndexer()))
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    var indexer = new ArticleIndexer(ramDir, config, store, db, uriRepo, null, inject[ShoeboxServiceClient])

    var (uri1, uri2, uri3) = db.readWrite { implicit s =>
      val user1 = userRepo.save(User(firstName = "Joe", lastName = "Smith"))
      val user2 = userRepo.save(User(firstName = "Moo", lastName = "Brown"))
      (uriRepo.save(
        NormalizedURIFactory(title = "a1", url = "http://www.keepit.com/article1", state = SCRAPED)),
          uriRepo.save(
            NormalizedURIFactory(title = "a2", url = "http://www.keepit.org/article2", state = SCRAPED)),
          uriRepo.save(
            NormalizedURIFactory(title = "a3", url = "http://www.findit.com/article3", state = SCRAPED)))
    }
    store += (uri1.id.get -> mkArticle(uri1.id.get, "title1 titles", "content1 alldocs body soul"))
    store += (uri2.id.get -> mkArticle(uri2.id.get, "title2 titles", "content2 alldocs bodies soul"))
    store += (uri3.id.get -> mkArticle(uri3.id.get, "title3 titles", "content3 alldocs bodies souls"))

    // saving ids for the search test
    uriIdArray(0) = uri1.id.get.id
    uriIdArray(1) = uri2.id.get.id
    uriIdArray(2) = uri3.id.get.id

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

    class Searchable(indexer: ArticleIndexer) {
      def search(queryString: String, percentMatch: Float = 0.0f): Seq[Hit] = {
        val parser = parserFactory(Lang("en"), siteBoost = 1.0f)
        parser.setPercentMatch(percentMatch)
        val searcher = indexer.getSearcher
        parser.parse(queryString) match {
          case Some(query) => searcher.search(query)
          case None => Seq.empty[Hit]
        }
      }
    }
    implicit def toSearchable(indexer: ArticleIndexer) = new Searchable(indexer)
  }

  "ArticleIndexer" should {
    "index indexable URIs" in running(new DevApplication().withShoeboxServiceModule)(new IndexerScope {
      db.readWrite { implicit s =>
        uri1 = uriRepo.save(uriRepo.get(uri1.id.get).withState(INACTIVE))
        uri2 = uriRepo.save(uriRepo.get(uri2.id.get).withState(INACTIVE))
        uri3 = uriRepo.save(uriRepo.get(uri3.id.get).withState(INACTIVE))
      }
      indexer.run()
      indexer.numDocs === 0

      var currentSeqNum = indexer.sequenceNumber.value

      db.readWrite { implicit s =>
        uri2 = uriRepo.save(uriRepo.get(uri2.id.get).withState(SCRAPED))
      }
      indexer.sequenceNumber.value === currentSeqNum
      indexer.run()
      currentSeqNum += 1
      indexer.sequenceNumber.value === currentSeqNum
      indexer.numDocs === 1

      db.readWrite { implicit s =>
        uri1 = uriRepo.save(uri1.withState(SCRAPED))
        uri2 = uriRepo.save(uri2.withState(SCRAPED))
        uri3 = uriRepo.save(uri3.withState(SCRAPED))
      }

      indexer.run()
      currentSeqNum += 3
      indexer.sequenceNumber.value === currentSeqNum
      indexer.numDocs === 3

      indexer = new ArticleIndexer(ramDir, config, store, db, uriRepo, null, inject[ShoeboxServiceClient])
      indexer.sequenceNumber.value === currentSeqNum
    })

    "search documents (hits in contents)" in running(new DevApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.run()

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
    })

    "search documents (hits in titles)" in running(new DevApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.run()

      var res = indexer.search("title1")
      res.size === 1
      res.head.id === uriIdArray(0)

      res = indexer.search("title2")
      res.size === 1
      res.head.id === uriIdArray(1)

      res = indexer.search("title3")
      res.size === 1
      res.head.id === uriIdArray(2)
    })

    "search documents (hits in contents and titles)" in running(new DevApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.run()

      var res = indexer.search("title1 alldocs")
      res.size === 3
      res.head.id === uriIdArray(0)

      res = indexer.search("title2 alldocs")
      res.size === 3
      res.head.id === uriIdArray(1)

      res = indexer.search("title3 alldocs")
      res.size === 3
      res.head.id === uriIdArray(2)
    })

    "search documents using stemming" in running(new DevApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.run()

      indexer.search("alldoc").size === 3
      indexer.search("title").size === 3
      indexer.search("bodies").size === 3
      indexer.search("soul").size === 3
      indexer.search("+bodies +souls").size === 3
      indexer.search("+body +soul").size === 3
    })

    "limit the result by percentMatch" in running(new DevApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.run()

      var res = indexer.search("title1 alldocs", percentMatch = 0.0f)
      res.size === 3

      res = indexer.search("title1 alldocs", percentMatch = 40.0f)
      res.size === 3

      res = indexer.search("title1 alldocs", percentMatch = 60.0f)
      res.size === 1

      res = indexer.search("title1 title2 alldocs", percentMatch = 60.0f)
      res.size === 2

      res = indexer.search("title1 title2 alldocs", percentMatch = 75.0f)
      res.size === 0
    })

    "limit the result by site" in running(new DevApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.run()

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
    })

    "match on the URI" in running(new DevApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.run()

      var res = indexer.search("keepit")
      res.size === 2

      res = indexer.search("findit")
      res.size === 1

      res = indexer.search("findit.com")
      res.size === 1
    })

    "be able to dump Lucene Document" in running(new DevApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.run()

      store += (uri1.id.get -> mkArticle(uri1.id.get, "title1 titles", "content1 alldocs body soul"))

      val doc = indexer.buildIndexable(uri1.id.get).buildDocument
      doc.getFields.forall{ f => indexer.getFieldDecoder(f.name).apply(f).length > 0 } === true
    })

    "delete documents with inactive, active, unscrapable, oe scrape_wanted state" in running(new DevApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.run()
      indexer.numDocs == 3

      db.readWrite { implicit s =>
        uri1 = uriRepo.save(uriRepo.get(uri1.id.get).withState(ACTIVE))
      }
      indexer.run()
      indexer.numDocs === 2
      indexer.search("content1").size === 0
      indexer.search("content2").size === 1
      indexer.search("content3").size === 1

      db.readWrite { implicit s =>
        uri1 = uriRepo.save(uriRepo.get(uri1.id.get).withState(SCRAPED))
        uri2 = uriRepo.save(uriRepo.get(uri2.id.get).withState(INACTIVE))
      }
      indexer.run()
      indexer.numDocs === 2
      indexer.search("content1").size === 1
      indexer.search("content2").size === 0
      indexer.search("content3").size === 1

      db.readWrite { implicit s =>
        uri1 = uriRepo.save(uri1.withState(SCRAPED))
        uri2 = uriRepo.save(uri2.withState(SCRAPED))
        uri3 = uriRepo.save(uri3.withState(UNSCRAPABLE))
      }
      indexer.run()
      indexer.numDocs === 2
      indexer.search("content1").size === 1
      indexer.search("content2").size === 1
      indexer.search("content3").size === 0

      db.readWrite { implicit s =>
        uri1 = uriRepo.save(uri1.withState(SCRAPE_WANTED))
        uri2 = uriRepo.save(uri2.withState(SCRAPED))
        uri3 = uriRepo.save(uri3.withState(SCRAPED))
      }
      indexer.run()
      indexer.numDocs === 2
      indexer.search("content1").size === 0
      indexer.search("content2").size === 1
      indexer.search("content3").size === 1
    })
  }
}
