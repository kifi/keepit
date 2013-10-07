package com.keepit.search

import com.keepit.search.graph.CollectionSearcherWithUser
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphSearcherWithUser
import com.keepit.search.index.ArticleIndexer
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.logging.Logging
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.time._
import com.keepit.model._
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import com.keepit.search.query.parser.SpellCorrector
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.shoebox.ClickHistoryTracker
import com.keepit.shoebox.BrowsingHistoryTracker
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.akka.MonitoredAwait
import scala.concurrent._
import scala.concurrent.duration._

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
    val uriGraphSearcher = getURIGraphSearcher(userId)
    val collectionSearcher = getCollectionSearcher(userId)
    val articleSearcher = articleIndexer.getSearcher
    val clickBoostsFuture = getClickBoostsFuture(userId, queryString, config.asFloat("maxResultClickBoost"), config.asBoolean("useS3FlowerFilter"))
    val browsingHistoryFuture = shoeboxClient.getBrowsingHistoryFilter(userId).map(browsingHistoryBuilder.build)
    val clickHistoryFuture = shoeboxClient.getClickHistoryFilter(userId).map(clickHistoryBuilder.build)

    new MainSearcher(
        userId,
        queryString,
        langProbabilities,
        numHitsToReturn,
        filter,
        config,
        lastUUID,
        articleSearcher,
        uriGraphSearcher,
        collectionSearcher,
        parserFactory,
        clickBoostsFuture,
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

  private[this] def getURIGraphSearcherFuture(userId: Id[User]) = consolidateURIGraphSearcherReq(userId){ userId =>
    Promise[URIGraphSearcherWithUser].success(uriGraph.getURIGraphSearcher(userId)).future
  }

  def getURIGraphSearcher(userId: Id[User]): URIGraphSearcherWithUser = {
    Await.result(getURIGraphSearcherFuture(userId), 5 seconds)
  }

  private[this] def getCollectionSearcherFuture(userId: Id[User]) = consolidateCollectionSearcherReq(userId){ userId =>
    Promise[CollectionSearcherWithUser].success(uriGraph.getCollectionSearcher(userId)).future
  }

  def getCollectionSearcher(userId: Id[User]): CollectionSearcherWithUser = {
    Await.result(getCollectionSearcherFuture(userId), 5 seconds)
  }

  private[this] def getClickBoostsFuture(userId: Id[User], queryString: String, maxResultClickBoost: Float, useS3FlowerFilter: Boolean) = {
    future {
      resultClickTracker.getBoosts(userId, queryString, maxResultClickBoost, useS3FlowerFilter)
    }
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
