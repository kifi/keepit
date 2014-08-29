package com.keepit.search.engine.result

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.search.{ SearchConfigExperiment, ArticleSearchResult }

class KifiPlainResult(val query: String, val result: KifiShardResult, val idFilter: Set[Long], val searchExperimentId: Option[Id[SearchConfigExperiment]]) {
  val uuid = ExternalId[ArticleSearchResult]()

  def hits: Seq[KifiShardHit] = result.hits
  def myTotal: Int = result.myTotal
  def friendsTotal: Int = result.friendsTotal
  def show: Boolean = result.show
  def mayHaveMoreHits = (result.myTotal + result.friendsTotal + result.othersTotal > result.hits.size)
}

