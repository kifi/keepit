package com.keepit.search.article

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.NormalizedURIStates._
import com.keepit.model._
import com.keepit.scraper.FakeArticleStore
import com.keepit.search.Article
import com.keepit.search.Lang
import com.keepit.search.query.parser.MainQueryParserFactory
import com.keepit.search.phrasedetector._
import com.keepit.test._
import com.keepit.inject._
import org.specs2.mutable._
import org.specs2.specification.Scope
import play.api.test.Helpers._
import scala.collection.JavaConversions._
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.shoebox.{FakeShoeboxServiceClientImpl, ShoeboxServiceClient}
import com.keepit.search.SearchConfig
import com.google.inject.Singleton
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.SearcherHit
import com.keepit.search.index.VolatileIndexDirectoryImpl
import com.keepit.search.phrasedetector.FakePhraseIndexer
import com.keepit.search.sharding.Shard
import scala.concurrent.Await
import scala.concurrent.duration._

class ArticleIndexerTest extends Specification with ApplicationInjector {

  private trait IndexerScope extends Scope {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    val ramDir = new VolatileIndexDirectoryImpl()
    val store = new FakeArticleStore()
    val uriIdArray = new Array[Long](3)
    val parserFactory = new MainQueryParserFactory(new PhraseDetector(new FakePhraseIndexer()), inject[MonitoredAwait])
    val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing)
    var indexer = new StandaloneArticleIndexer(ramDir, config, store, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])

    val Seq(user1, user2) = fakeShoeboxServiceClient.saveUsers(User(firstName = "Joe", lastName = "Smith"), User(firstName = "Moo", lastName = "Brown"))
    var Seq(uri1, uri2, uri3) = fakeShoeboxServiceClient.saveURIs(
      NormalizedURI.withHash(title = Some("title1 titles"), normalizedUrl = "http://www.keepit.com/article1", state = SCRAPED),
      NormalizedURI.withHash(title = Some("title2 titles"), normalizedUrl = "http://www.keepit.org/article2", state = SCRAPED),
      NormalizedURI.withHash(title = Some("title3 titles"), normalizedUrl = "http://www.find-it.com/article3", state = SCRAPED)
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
        canonicalUrl = None,
        alternateUrls = Set.empty,
        keywords = None,
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

    val searchConfig = SearchConfig.defaultConfig.overrideWith("siteBoost" -> "1.0")

    class Searchable(indexer: ArticleIndexer) {
      def search(queryString: String, percentMatch: Float = 0.0f): Seq[SearcherHit] = {
        val parser = parserFactory(Lang("en"), searchConfig)
        parser.setPercentMatch(percentMatch)
        val searcher = indexer.getSearcher.withSemanticContext
        parser.parse(queryString, None) match {
          case Some(query) => searcher.search(query)
          case None => Seq.empty[SearcherHit]
        }
      }
    }
    implicit def toSearchable(indexer: ArticleIndexer) = new Searchable(indexer)
  }

  "ArticleIndexer" should {
    "index indexable URIs" in running(new DeprecatedEmptyApplication().withShoeboxServiceModule())(new IndexerScope {

      uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(INACTIVE)).head
      uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(INACTIVE)).head
      uri3 = fakeShoeboxServiceClient.saveURIs(uri3.withState(INACTIVE)).head

      indexer.update()
      indexer.numDocs === 0

      var currentSeqNum = indexer.sequenceNumber.value

      uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(SCRAPED)).head

      indexer.sequenceNumber.value === currentSeqNum
      indexer.update()
      currentSeqNum += 1
      indexer.sequenceNumber.value === currentSeqNum
      indexer.numDocs === 1

      uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(SCRAPED)).head
      uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(SCRAPED)).head
      uri3 = fakeShoeboxServiceClient.saveURIs(uri3.withState(SCRAPED)).head

      indexer.update()
      currentSeqNum += 3
      indexer.sequenceNumber.value === currentSeqNum
      indexer.numDocs === 3

      indexer = new StandaloneArticleIndexer(ramDir, config, store, null, inject[ShoeboxServiceClient])
      indexer.sequenceNumber.value === currentSeqNum
    })

    "search documents (hits in contents)" in running(new DeprecatedEmptyApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.update()

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

    "search documents (hits in titles)" in running(new DeprecatedEmptyApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.update()

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

    "search documents (hits in contents and titles)" in running(new DeprecatedEmptyApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.update()

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

    "search documents using stemming" in running(new DeprecatedEmptyApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.update()

      indexer.search("alldoc").size === 3
      indexer.search("title").size === 3
      indexer.search("bodies").size === 3
      indexer.search("soul").size === 3
      indexer.search("+bodies +souls").size === 3
      indexer.search("+body +soul").size === 3
    })

    "limit the result by percentMatch" in running(new DeprecatedEmptyApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.update()

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

    "limit the result by site" in running(new DeprecatedEmptyApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.update()

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

    "match on the URI" in running(new DeprecatedEmptyApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.update()

      var res = indexer.search("keepit")
      res.size === 2

      res = indexer.search("keepit.com")
      res.size === 1

      res = indexer.search("find-it")
      res.size === 1

      res = indexer.search("find")
      res.size === 1

      res = indexer.search("find-it.com")
      res.size === 1
    })

    "be able to dump Lucene Document" in running(new DeprecatedEmptyApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.update()

      store += (uri1.id.get -> mkArticle(uri1.id.get, "title1 titles", "content1 alldocs body soul"))

      val doc = indexer.buildIndexable(IndexableUri(uri1)).buildDocument
      doc.getFields.forall{ f => indexer.getFieldDecoder(f.name).apply(f).length > 0 } === true
    })

    "delete documents with inactive, active, unscrapable, scrape_wanted or scrape_later state" in running(new DeprecatedEmptyApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.update()
      indexer.numDocs === 3

      uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(ACTIVE)).head
      indexer.update()
      indexer.numDocs === 2
      indexer.search("content1").size === 0
      indexer.search("content2").size === 1
      indexer.search("content3").size === 1

      uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(SCRAPED)).head
      uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(INACTIVE)).head

      indexer.update()
      indexer.numDocs === 2
      indexer.search("content1").size === 1
      indexer.search("content2").size === 0
      indexer.search("content3").size === 1

      uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(SCRAPED)).head
      uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(SCRAPED)).head
      uri3 = fakeShoeboxServiceClient.saveURIs(uri3.withState(UNSCRAPABLE)).head

      indexer.update()
      indexer.numDocs === 2
      indexer.search("content1").size === 1
      indexer.search("content2").size === 1
      indexer.search("content3").size === 0

      uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(SCRAPE_WANTED)).head
      uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(SCRAPED)).head
      uri3 = fakeShoeboxServiceClient.saveURIs(uri3.withState(SCRAPED)).head

      indexer.update()
      indexer.numDocs === 2
      indexer.search("content1").size === 0
      indexer.search("content2").size === 1
      indexer.search("content3").size === 1
    })

    "retrieve article records from index" in running(new DeprecatedEmptyApplication().withShoeboxServiceModule)(new IndexerScope {
      indexer.update()
      indexer.numDocs === 3
      import com.keepit.search.article.ArticleRecordSerializer._

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
