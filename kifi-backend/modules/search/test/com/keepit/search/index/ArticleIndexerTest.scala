package com.keepit.search.index

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.common.healthcheck.HealthcheckPlugin
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
import org.apache.lucene.store.RAMDirectory
import org.specs2.specification.Scope
import play.api.test.Helpers._
import scala.collection.JavaConversions._
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.shoebox.{FakeShoeboxServiceClientImpl, ShoeboxServiceClient}

class ArticleIndexerTest extends Specification {

  private trait IndexerScope extends Scope {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    val ramDir = new RAMDirectory
    val store = new FakeArticleStore()
    val uriIdArray = new Array[Long](3)
    val parserFactory = new MainQueryParserFactory(new PhraseDetector(new FakePhraseIndexer()))
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    var indexer = new ArticleIndexer(ramDir, config, store, inject[HealthcheckPlugin], inject[ShoeboxServiceClient])

    val Seq(user1, user2) = fakeShoeboxServiceClient.saveUsers(User(firstName = "Joe", lastName = "Smith"), User(firstName = "Moo", lastName = "Brown"))
    var Seq(uri1, uri2, uri3) = fakeShoeboxServiceClient.saveURIs(
      NormalizedURIFactory(title = "title1 titles", url = "http://www.keepit.com/article1", state = SCRAPED),
      NormalizedURIFactory(title = "title2 titles", url = "http://www.keepit.org/article2", state = SCRAPED),
      NormalizedURIFactory(title = "title3 titles", url = "http://www.findit.com/article3", state = SCRAPED)
    )
    store += (uri1.id.get -> mkArticle(uri1.id.get, uri1.title.get, "content1 alldocs body soul"))
    store += (uri2.id.get -> mkArticle(uri2.id.get, uri2.title.get, "content2 alldocs bodies soul"))
    store += (uri3.id.get -> mkArticle(uri3.id.get, uri3.title.get, "content3 alldocs bodies souls"))

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
    "index indexable URIs" in running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule())(new IndexerScope {

      uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(INACTIVE)).head
      uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(INACTIVE)).head
      uri3 = fakeShoeboxServiceClient.saveURIs(uri3.withState(INACTIVE)).head

      indexer.run()
      indexer.numDocs === 0

      var currentSeqNum = indexer.sequenceNumber.value

      uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(SCRAPED)).head

      indexer.sequenceNumber.value === currentSeqNum
      indexer.run()
      currentSeqNum += 1
      indexer.sequenceNumber.value === currentSeqNum
      indexer.numDocs === 1

      uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(SCRAPED)).head
      uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(SCRAPED)).head
      uri3 = fakeShoeboxServiceClient.saveURIs(uri3.withState(SCRAPED)).head

      indexer.run()
      currentSeqNum += 3
      indexer.sequenceNumber.value === currentSeqNum
      indexer.numDocs === 3

      indexer = new ArticleIndexer(ramDir, config, store, null, inject[ShoeboxServiceClient])
      indexer.sequenceNumber.value === currentSeqNum
    })

    "search documents (hits in contents)" in running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule)(new IndexerScope {
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

    "search documents (hits in titles)" in running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule)(new IndexerScope {
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

    "search documents (hits in contents and titles)" in running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule)(new IndexerScope {
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

    "search documents using stemming" in running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule)(new IndexerScope {
      indexer.run()

      indexer.search("alldoc").size === 3
      indexer.search("title").size === 3
      indexer.search("bodies").size === 3
      indexer.search("soul").size === 3
      indexer.search("+bodies +souls").size === 3
      indexer.search("+body +soul").size === 3
    })

    "limit the result by percentMatch" in running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule)(new IndexerScope {
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

    "limit the result by site" in running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule)(new IndexerScope {
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

    "match on the URI" in running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule)(new IndexerScope {
      indexer.run()

      var res = indexer.search("keepit")
      res.size === 2

      res = indexer.search("findit")
      res.size === 1

      res = indexer.search("findit.com")
      res.size === 1
    })

    "be able to dump Lucene Document" in running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule)(new IndexerScope {
      indexer.run()

      store += (uri1.id.get -> mkArticle(uri1.id.get, "title1 titles", "content1 alldocs body soul"))

      val doc = indexer.buildIndexable(uri1.id.get).buildDocument
      doc.getFields.forall{ f => indexer.getFieldDecoder(f.name).apply(f).length > 0 } === true
    })

    "delete documents with inactive, active, unscrapable, oe scrape_wanted state" in running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule)(new IndexerScope {
      indexer.run()
      indexer.numDocs === 3

      uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(ACTIVE)).head
      indexer.run()
      indexer.numDocs === 2
      indexer.search("content1").size === 0
      indexer.search("content2").size === 1
      indexer.search("content3").size === 1

      uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(SCRAPED)).head
      uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(INACTIVE)).head

      indexer.run()
      indexer.numDocs === 2
      indexer.search("content1").size === 1
      indexer.search("content2").size === 0
      indexer.search("content3").size === 1

      uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(SCRAPED)).head
      uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(SCRAPED)).head
      uri3 = fakeShoeboxServiceClient.saveURIs(uri3.withState(UNSCRAPABLE)).head

      indexer.run()
      indexer.numDocs === 2
      indexer.search("content1").size === 1
      indexer.search("content2").size === 1
      indexer.search("content3").size === 0

      uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(SCRAPE_WANTED)).head
      uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(SCRAPED)).head
      uri3 = fakeShoeboxServiceClient.saveURIs(uri3.withState(SCRAPED)).head

      indexer.run()
      indexer.numDocs === 2
      indexer.search("content1").size === 0
      indexer.search("content2").size === 1
      indexer.search("content3").size === 1
    })

    "retrieve article records from index" in running(new EmptyApplication().withFakePersistEvent().withShoeboxServiceModule)(new IndexerScope {
      import com.keepit.search.index.ArticleRecordSerializer._
      indexer.run()
      indexer.numDocs === 3

      val searcher = indexer.getSearcher
      Seq(uri1, uri2, uri3).map{ uri =>
        val recOpt: Option[ArticleRecord] = searcher.getDecodedDocValue("rec", uri.id.get.id)
        recOpt must beSome[ArticleRecord]
        recOpt.map{ rec =>
          rec.title === uri.title.get
          rec.url === uri.url
        }
      }
      val recOpt: Option[ArticleRecord] = searcher.getDecodedDocValue("rec", 999999L)
      recOpt must beNone
    })
  }
}
