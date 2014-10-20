package com.keepit.search.engine.result

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.search.{ SearchFilter, Lang, SearchConfigExperiment, ArticleSearchResult }

object KifiPlainResult {
  def apply(query: String, searchFilter: SearchFilter, firstLang: Lang, result: KifiShardResult, idFilter: Set[Long], searchExperimentId: Option[Id[SearchConfigExperiment]]): KifiPlainResult = {
    new KifiPlainResult(ExternalId[ArticleSearchResult](), query, searchFilter: SearchFilter, firstLang, result, idFilter, searchExperimentId)
  }
}

class KifiPlainResult(val uuid: ExternalId[ArticleSearchResult], val query: String, searchFilter: SearchFilter, val firstLang: Lang, val result: KifiShardResult, val idFilter: Set[Long], val searchExperimentId: Option[Id[SearchConfigExperiment]]) {
  def hits: Seq[KifiShardHit] = result.hits
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

