package com.keepit.search.index.article

import com.google.inject.Injector
import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.common.util.PlayAppConfigurationModule
import com.keepit.model.IndexableUri
import com.keepit.model.NormalizedURI
import com.keepit.model.NormalizedURIStates._
import com.keepit.model.NormalizedURIStates._
import com.keepit.model.UserFactory
import com.keepit.model._
import com.keepit.rover.{ FakeRoverServiceClientImpl, RoverServiceClient }
import com.keepit.search._
import com.keepit.search.engine.parser.DefaultSyntax
import com.keepit.search.engine.parser.KQueryExpansion
import com.keepit.search.engine.parser.QueryParser
import com.keepit.search.index.Analyzer
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.Indexable
import com.keepit.search.index.SearcherHit
import com.keepit.search.index.VolatileIndexDirectory
import com.keepit.search.index.sharding.{ Shard, ShardSpecParser, ActiveShards }
import com.keepit.search.test.SearchTestInjector
import com.keepit.search.engine.parser.KQueryExpansion
import com.keepit.search.engine.parser._
import com.keepit.search.test.SearchTestInjector
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.shoebox.ShoeboxServiceClient
import org.specs2.mutable._
import org.specs2.specification.Scope
import scala.collection.JavaConversions._
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, ShoeboxServiceClient }
import com.keepit.search.index._
import com.keepit.common.util.PlayAppConfigurationModule

import scala.concurrent.ExecutionContext

class ArticleIndexerTest extends Specification with SearchTestInjector with SearchTestHelper {

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
    def getPrefixBoost(trailing: Boolean): Float = 0.0f
  }

  private class IndexerScope(injector: Injector) extends Scope {
    implicit val inj = injector
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    val fakeRoverServiceClientImpl = inject[RoverServiceClient].asInstanceOf[FakeRoverServiceClientImpl]

    val singleShard = Shard[NormalizedURI](0, 1)
    implicit val activeShards: ActiveShards = ActiveShards(Set(singleShard))
    val ramDir = new VolatileIndexDirectory()

    def newIndexer = new ArticleIndexer(ramDir, singleShard, null)
    var indexer = newIndexer
    val shardedIndexer = new ShardedArticleIndexer(Map(singleShard -> indexer), fakeShoeboxServiceClient, fakeRoverServiceClientImpl, null, inject[ExecutionContext])

    var Seq(uri1, uri2, uri3) = fakeShoeboxServiceClient.saveURIs(
      NormalizedURI.withHash(title = Some("title1 titles"), normalizedUrl = "http://www.keepit.com/article1").withContentRequest(true),
      NormalizedURI.withHash(title = Some("title2 titles"), normalizedUrl = "http://www.keepit.org/article2").withContentRequest(true),
      NormalizedURI.withHash(title = Some("title3 titles"), normalizedUrl = "http://www.find-it.com/article3").withContentRequest(true)
    )

    fakeRoverServiceClientImpl.setArticlesForUri(uri1.id.get, Set(mkEmbedlyArticle(uri1.url, uri1.title.get, "content1 alldocs body soul this is in English")))
    fakeRoverServiceClientImpl.setArticlesForUri(uri2.id.get, Set(mkEmbedlyArticle(uri2.url, uri2.title.get, "content2 alldocs body soul this is in English")))
    fakeRoverServiceClientImpl.setArticlesForUri(uri3.id.get, Set(mkEmbedlyArticle(uri3.url, uri3.title.get, "content3 alldocs body soul this is in English")))

    val uriIdArray = Array(uri1.id.get.id, uri2.id.get.id, uri3.id.get.id)

    lazy val shoeboxClient = inject[ShoeboxServiceClient]

    class Searchable(indexer: ArticleIndexer) {
      def search(queryString: String): Seq[SearcherHit] = {
        val searcher = indexer.getSearcher
        (new TstQueryParser).parse(queryString) match {
          case Some(query) => searcher.searchAll(query)
          case None => Seq.empty[SearcherHit]
        }
      }
    }
    implicit def toSearchable(indexer: ArticleIndexer) = new Searchable(indexer)
  }

  "ArticleIndexer" should {
    "index indexable URIs" in {
      withInjector(helperModules: _*) { injector =>
        new IndexerScope(injector) {
          uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(INACTIVE)).head
          uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(REDIRECTED)).head
          uri3 = fakeShoeboxServiceClient.saveURIs(uri3.copy(shouldHaveContent = false)).head

          var currentSeqNum = -1L

          shardedIndexer.sequenceNumber.value === currentSeqNum

          updateNow(shardedIndexer)
          currentSeqNum = Seq(uri1, uri2, uri3).map(_.seq).max.value
          shardedIndexer.sequenceNumber.value === currentSeqNum
          indexer.sequenceNumber.value === -1
          indexer.numDocs === 0

          uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(ACTIVE)).head

          updateNow(shardedIndexer)
          currentSeqNum += 1
          shardedIndexer.sequenceNumber.value === currentSeqNum
          indexer.sequenceNumber.value === currentSeqNum
          indexer.numDocs === 1

          uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withState(ACTIVE)).head
          uri3 = fakeShoeboxServiceClient.saveURIs(uri3.withContentRequest(true)).head

          updateNow(shardedIndexer)
          currentSeqNum += 2
          shardedIndexer.sequenceNumber.value === currentSeqNum
          indexer.sequenceNumber.value === currentSeqNum
          indexer.numDocs === 3
          indexer.close()

          indexer = newIndexer
          indexer.sequenceNumber.value === currentSeqNum
        }
      }
    }

    "search documents (hits in contents)" in {
      withInjector(helperModules: _*) { injector =>
        new IndexerScope(injector) {
          updateNow(shardedIndexer)

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
          updateNow(shardedIndexer)

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
          updateNow(shardedIndexer)

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
          updateNow(shardedIndexer)

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
          updateNow(shardedIndexer)

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
          updateNow(shardedIndexer)

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

    "delete documents of uris that are inactive, redirected, or active without content" in {
      withInjector(helperModules: _*) { injector =>
        new IndexerScope(injector) {
          updateNow(shardedIndexer)
          indexer.numDocs === 3

          uri1 = fakeShoeboxServiceClient.saveURIs(uri1.copy(shouldHaveContent = false)).head
          updateNow(shardedIndexer)
          indexer.numDocs === 2
          indexer.search("content1").size === 0
          indexer.search("content2").size === 1
          indexer.search("content3").size === 1

          uri1 = fakeShoeboxServiceClient.saveURIs(uri1.withContentRequest(true)).head
          uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(INACTIVE)).head

          updateNow(shardedIndexer)
          indexer.numDocs === 2
          indexer.search("content1").size === 1
          indexer.search("content2").size === 0
          indexer.search("content3").size === 1

          uri2 = fakeShoeboxServiceClient.saveURIs(uri2.withState(ACTIVE)).head
          uri3 = fakeShoeboxServiceClient.saveURIs(uri3.withState(REDIRECTED)).head

          updateNow(shardedIndexer)
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
          updateNow(shardedIndexer)
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
