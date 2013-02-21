package com.keepit.search

import com.keepit.search.graph.URIGraph
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

@Singleton
class MainSearcherFactory @Inject() (
    articleIndexer: ArticleIndexer,
    uriGraph: URIGraph,
    parserFactory: MainQueryParserFactory,
    resultClickTracker: ResultClickTracker,
    browsingHistoryTracker: BrowsingHistoryTracker,
    clickHistoryTracker: ClickHistoryTracker
 ) {

  def apply(userId: Id[User], friendIds: Set[Id[User]], filterOut: Set[Long], config: SearchConfig) = {
    val articleSearcher = articleIndexer.getSearcher
    val uriGraphSearcher = uriGraph.getURIGraphSearcher
    new MainSearcher(
        userId,
        friendIds,
        filterOut,
        config,
        articleSearcher,
        uriGraphSearcher,
        parserFactory,
        resultClickTracker,
        browsingHistoryTracker,
        clickHistoryTracker
    )
  }

  def bookmarkSearcher(userId: Id[User]) = {
    val articleSearcher = articleIndexer.getSearcher
    val uriGraphSearcher = uriGraph.getURIGraphSearcher
    new BookmarkSearcher(userId, articleSearcher, uriGraphSearcher)
  }

  def semanticVectorSearcher() = {
    val articleSearcher = articleIndexer.getSearcher
    val uriGraphSearcher = uriGraph.getURIGraphSearcher
    new SemanticVectorSearcher(articleSearcher, uriGraphSearcher)
  }
}
