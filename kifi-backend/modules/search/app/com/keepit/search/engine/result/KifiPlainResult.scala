package com.keepit.search.engine.result

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.search.{ SearchConfigExperiment, ArticleSearchResult }

object KifiPlainResult {
  def apply(query: String, result: KifiShardResult, idFilter: Set[Long], searchExperimentId: Option[Id[SearchConfigExperiment]]): KifiPlainResult = {
    new KifiPlainResult(ExternalId[ArticleSearchResult](), query, result, idFilter, searchExperimentId)
  }
}

class KifiPlainResult(val uuid: ExternalId[ArticleSearchResult], val query: String, val result: KifiShardResult, val idFilter: Set[Long], val searchExperimentId: Option[Id[SearchConfigExperiment]]) {
  def hits: Seq[KifiShardHit] = result.hits
  def myTotal: Int = result.myTotal
  def friendsTotal: Int = result.friendsTotal
  def othersTotal: Int = result.othersTotal
  def show: Boolean = result.show
  def cutPoint: Int = result.cutPoint
  def mayHaveMoreHits = (result.myTotal + result.friendsTotal + result.othersTotal > result.hits.size)
}

