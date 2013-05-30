package com.keepit.search

import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphUnsupportedVersionException
import com.keepit.search.graph.URIList
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.PersonalizedSearcher
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.logging.Logging
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
import com.keepit.common.akka.{MonitoredAwait, MonitoredFuture}
import scala.concurrent.Future
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
    monitoredFuture: MonitoredFuture,
    implicit private val clock: Clock,
    implicit private val fortyTwoServices: FortyTwoServices
 ) extends Logging {

  def apply(userId: Id[User], friendIdsFuture: Future[Set[Id[User]]], filter: SearchFilter, config: SearchConfig) = {
    val articleSearcher = articleIndexer.getSearcher
    val uriGraphSearcher = {
      try {
        uriGraph.getURIGraphSearcher(Some(userId))
      } catch {
        case e: URIGraphUnsupportedVersionException =>
          // self healing, just in case
          log.warn("fixing graph data", e)
          uriGraph.update(userId)
          uriGraph.getURIGraphSearcher(Some(userId))
      }
    }
    
    val browsingHistoryFuture = shoeboxClient.getBrowsingHistoryFilter(userId).map(browsingHistoryBuilder.build)
    val clickHistoryFuture = shoeboxClient.getClickHistoryFilter(userId).map(clickHistoryBuilder.build)
    val friendIds = monitoredAwait.result(friendIdsFuture, 5 seconds)

    new MainSearcher(
        userId,
        friendIds,
        filter,
        config,
        articleSearcher,
        uriGraphSearcher,
        parserFactory,
        resultClickTracker,
        browsingHistoryFuture,
        clickHistoryFuture,
        shoeboxClient,
        spellCorrector,
        monitoredAwait,
        monitoredFuture
    )
  }

  def bookmarkSearcher(userId: Id[User]) = {
    val articleSearcher = articleIndexer.getSearcher
    val uriGraphSearcher = uriGraph.getURIGraphSearcher(Some(userId))
    new BookmarkSearcher(userId, articleSearcher, uriGraphSearcher)
  }

  def semanticVectorSearcher() = {
    val articleSearcher = articleIndexer.getSearcher
    val uriGraphSearcher = uriGraph.getURIGraphSearcher()
    new SemanticVectorSearcher(articleSearcher, uriGraphSearcher)
  }
}
