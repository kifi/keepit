package com.keepit.search.engine.uri

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.search.{ ArticleSearchResult, Lang, SearchConfigExperiment, SearchFilter }

object UriSearchResult {
  def apply(query: String, searchFilter: SearchFilter, firstLang: Lang, result: UriShardResult, idFilter: Set[Long], searchExperimentId: Option[Id[SearchConfigExperiment]]): UriSearchResult = {
    new UriSearchResult(ExternalId[ArticleSearchResult](), query, searchFilter: SearchFilter, firstLang, result, idFilter, searchExperimentId)
  }
}

class UriSearchResult(val uuid: ExternalId[ArticleSearchResult], val query: String, val searchFilter: SearchFilter, val firstLang: Lang, val result: UriShardResult, val idFilter: Set[Long], val searchExperimentId: Option[Id[SearchConfigExperiment]]) {
  def hits: Seq[UriShardHit] = result.hits
  def myTotal: Int = result.myTotal
  def friendsTotal: Int = result.friendsTotal
  def othersTotal: Int = result.othersTotal
  def show: Boolean = result.show
  def cutPoint: Int = result.cutPoint
  def mayHaveMoreHits = {
    val total = (if (searchFilter.includeMine) result.myTotal else 0) + (if (searchFilter.includeFriends) result.friendsTotal else 0) + (if (searchFilter.includeOthers) result.othersTotal else 0)
    result.hits.size < total
  }
}

