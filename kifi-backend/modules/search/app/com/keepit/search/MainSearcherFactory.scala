package com.keepit.search

import com.keepit.search.graph.CollectionSearcherWithUser
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphSearcherWithUser
import com.keepit.search.graph.URIGraphUnsupportedVersionException
import com.keepit.search.graph.URIList
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.PersonalizedSearcher
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.logging.Logging
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.time._
import com.keepit.model._
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.util.PriorityQueue
import java.util.UUID
import scala.math._
import org.joda.time.DateTime
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.search.query.parser.SpellCorrector
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.shoebox.ClickHistoryTracker
import com.keepit.shoebox.BrowsingHistoryTracker
import scala.concurrent.ExecutionContext.Implicits._
import com.keepit.common.akka.MonitoredAwait
import scala.concurrent.duration._
import scala.concurrent.future
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.Await

@Singleton
class MainSearcherFactory @Inject() (
    articleIndexer: ArticleIndexer,
    uriGraph: URIGraph,
    parserFactory: MainQueryParserFactory,
    resultClickTracker: ResultClickTracker,
    browsingHistoryBuilder: BrowsingHistoryBuilder,
    clickHistoryBuilder: ClickHistoryBuilder,
    shoeboxClient: ShoeboxServiceClient,
    spellCorrector: SpellCorrector,
    monitoredAwait: MonitoredAwait,
    implicit private val clock: Clock,
    implicit private val fortyTwoServices: FortyTwoServices
 ) extends Logging {

  private[this] val consolidateURIGraphSearcherReq = new RequestConsolidator[Id[User], URIGraphSearcherWithUser](3 seconds)
  private[this] val consolidateCollectionSearcherReq = new RequestConsolidator[Id[User], CollectionSearcherWithUser](3 seconds)

  def apply(
    userId: Id[User],
    queryString: String,
    langProbabilities: Map[Lang, Double],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig,
    lastUUID: Option[ExternalId[ArticleSearchResultRef]]
  ) = {
    val browsingHistoryFuture = shoeboxClient.getBrowsingHistoryFilter(userId).map(browsingHistoryBuilder.build)
    val clickHistoryFuture = shoeboxClient.getClickHistoryFilter(userId).map(clickHistoryBuilder.build)

    val uriGraphSearcherFuture = getURIGraphSearcherFuture(userId)
    val collectionSearcherFuture = getCollectionSearcherFuture(userId)
    val articleSearcher = articleIndexer.getSearcher

    new MainSearcher(
        userId,
        queryString,
        langProbabilities,
        numHitsToReturn,
        filter,
        config,
        lastUUID,
        articleSearcher,
        monitoredAwait.result(uriGraphSearcherFuture, 5 seconds, s"getting uri graph searcher for user Id $userId"),
        monitoredAwait.result(collectionSearcherFuture, 5 seconds, s"getting collection searcher for user Id $userId"),
        parserFactory,
        resultClickTracker,
        browsingHistoryFuture,
        clickHistoryFuture,
        shoeboxClient,
        spellCorrector,
        monitoredAwait
    )
  }

  def clear(): Unit = {
    consolidateURIGraphSearcherReq.clear()
    consolidateCollectionSearcherReq.clear()
  }

  def getURIGraphSearcherFuture(userId: Id[User]) = consolidateURIGraphSearcherReq(userId){ userId =>
    future {
      uriGraph.getURIGraphSearcher(userId)
    } recover {
      case e: URIGraphUnsupportedVersionException =>
      // self healing, just in case
      log.warn("fixing graph data", e)
      uriGraph.update(userId)
      uriGraph.getURIGraphSearcher(userId)
    }
  }

  def getURIGraphSearcher(userId: Id[User]): URIGraphSearcherWithUser = {
    monitoredAwait.result(getURIGraphSearcherFuture(userId), 5 seconds, s"getting uri graph searcher for user Id $userId")
  }

  def getCollectionSearcherFuture(userId: Id[User]) = consolidateCollectionSearcherReq(userId){ userId =>
    future {
      uriGraph.getCollectionSearcher(userId)
    }
  }

  def getCollectionSearcher(userId: Id[User]): CollectionSearcherWithUser = {
    monitoredAwait.result(getCollectionSearcherFuture(userId), 5 seconds, s"getting collection searcher for user Id $userId")
  }

  def bookmarkSearcher(userId: Id[User]) = {
    val articleSearcher = articleIndexer.getSearcher
    val uriGraphSearcher = uriGraph.getURIGraphSearcher(userId)
    new BookmarkSearcher(userId, articleSearcher, uriGraphSearcher)
  }

  def semanticVectorSearcher() = {
    val articleSearcher = articleIndexer.getSearcher
    val uriGraphSearcher = uriGraph.getURIGraphSearcher()
    new SemanticVectorSearcher(articleSearcher, uriGraphSearcher)
  }
}
