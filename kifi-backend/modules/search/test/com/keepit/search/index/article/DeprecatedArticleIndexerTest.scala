package com.keepit.search.index.article

import com.google.inject.Injector
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.NormalizedURIStates._
import com.keepit.model._
import com.keepit.search.{ InMemoryArticleStoreImpl, Article, Lang }
import com.keepit.search.engine.parser.KQueryExpansion
import com.keepit.search.engine.parser._
import com.keepit.search.test.SearchTestInjector
import org.specs2.mutable._
import org.specs2.specification.Scope
import scala.collection.JavaConversions._
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, ShoeboxServiceClient }
import com.keepit.search.index.{ SearcherHit, Analyzer, DefaultAnalyzer, VolatileIndexDirectory }
import com.keepit.common.util.PlayAppConfigurationModule

class DeprecatedArticleIndexerTest extends Specification with SearchTestInjector {

  val helperModules = Seq(PlayAppConfigurationModule())

  private[this] val en = Lang("en")
  private[this] val analyzer = DefaultAnalyzer.getAnalyzer(en)
  private[this] val stemmingAnalyzer = DefaultAnalyzer.getAnalyzerWithStemmer(en)

  private class TstQueryParser extends QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with KQueryExpansion {
    val lang: Lang = en
    val altAnalyzer: Option[Analyzer] = None
    val altStemmingAnalyzer: Option[Analyzer] = None
    val titleBoost: Float = 2.0f
    val siteBoost: Float = 1.0f
    val concatBoost: Float = 0.0f
    val prefixBoost: Float = 0.0f
  }

  private class IndexerScope(injector: Injector) extends Scope {
    implicit val inj = injector
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    val ramDir = new VolatileIndexDirectory()
    val store = new InMemoryArticleStoreImpl()
    val uriIdArray = new Array[Long](3)
    var indexer = new DeprecatedStandaloneArticleIndexer(ramDir, store, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])

    val Seq(user1, user2) = fakeShoeboxServiceClient.saveUsers(User(firstName = "Joe", lastName = "Smith", username = Username("test"), normalizedUsername = "test"), User(firstName = "Moo", lastName = "Brown", username = Username("test"), normalizedUsername = "test"))
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

    lazy val shoeboxClient = inject[ShoeboxServiceClient]

    def mkArticle(normalizedUriId: Id[NormalizedURI], title: String, content: String) = {
      Article(
        id = normalizedUriId,
        title = title,
        description = None,
        author = None,
        publishedAt = None,
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
        titleLang = Some(en),
        contentLang = Some(en))
    }

    class Searchable(indexer: DeprecatedArticleIndexer) {
      def search(queryString: String): Seq[SearcherHit] = {
        val searcher = indexer.getSearcher
        (new TstQueryParser).parse(queryString) match {
          case Some(query) => searcher.searchAll(query)
          case None => Seq.empty[SearcherHit]
        }
      }
    }
    implicit def toSearchable(indexer: DeprecatedArticleIndexer) = new Searchable(indexer)
  }

  "ArticleIndexer" should {
    "index indexable URIs" in {
      withInjector(helperModules: _*) { injector =>
        new IndexerScope(injector) {
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
          indexer.close()

          indexer = new DeprecatedStandaloneArticleIndexer(ramDir, store, null, shoeboxClient)
          indexer.sequenceNumber.value === currentSeqNum
        }
      }
    }

    "search documents (hits in contents)" in {
      withInjector(helperModules: _*) { injector =>
        new IndexerScope(injector) {
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
        }
      }
    }

    "search documents (hits in titles)" in {
      withInjector(helperModules: _*) { injector =>
        new IndexerScope(injector) {
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
        }
      }
    }

    "search documents (hits in contents and titles)" in {
      withInjector(helperModules: _*) { injector =>
        new IndexerScope(injector) {
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
        }
      }
    }

    "search documents using stemming" in {
      withInjector(helperModules: _*) { injector =>
        new IndexerScope(injector) {
          indexer.update()

          indexer.search("alldoc").size === 3
          indexer.search("title").size === 3
          indexer.search("bodies").size === 3
          indexer.search("soul").size === 3
          indexer.search("+bodies +souls").size === 3
          indexer.search("+body +soul").size === 3
        }
      }
    }

    "limit the result by site" in {
      withInjector(helperModules: _*) { injector =>
        new IndexerScope(injector) {
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
        }
      }
    }

    "match on the URI" in {
      withInjector(helperModules: _*) { injector =>
        new IndexerScope(injector) {
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
        }
      }
    }

    "be able to dump Lucene Document" in {
      withInjector(helperModules: _*) { injector =>
        new IndexerScope(injector) {
          indexer.update()

          store += (uri1.id.get -> mkArticle(uri1.id.get, "title1 titles", "content1 alldocs body soul"))

          val doc = indexer.buildIndexable(IndexableUri(uri1)).buildDocument
          doc.getFields.forall { f => indexer.getFieldDecoder(f.name).apply(f).length > 0 } === true
        }
      }
    }

    "delete documents with inactive, active, unscrapable, or scrape_later state" in {
      withInjector(helperModules: _*) { injector =>
        new IndexerScope(injector) {
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
        }
      }
    }

    "retrieve article records from index" in {
      withInjector(helperModules: _*) { injector =>
        new IndexerScope(injector) {
          indexer.update()
          indexer.numDocs === 3

          val searcher = indexer.getSearcher
          Seq(uri1, uri2, uri3).map { uri =>
            val recOpt: Option[ArticleRecord] = searcher.getDecodedDocValue("rec", uri.id.get.id)
            recOpt must beSome[ArticleRecord]
            recOpt.map { rec =>
              rec.title === uri.title
              rec.url === uri.url
            }
          }
          val recOpt: Option[ArticleRecord] = searcher.getDecodedDocValue("rec", 999999L)
          recOpt must beNone
        }
      }
    }
  }
}
