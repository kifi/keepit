package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash
import scala.math._
import com.keepit.model.NormalizedURI
import com.keepit.model.User



abstract class ResultClickBoosts {
  def apply(value: Long): Float
}

class ResultClickTracker(lru: ProbablisticLRU) {
  
  private[this] val analyzer = DefaultAnalyzer.defaultAnalyzer

  def add(userId: Id[User], query: String, uriId: Id[NormalizedURI], rank: Int, isUserKeep: Boolean) = {
    val hash = QueryHash(userId, query, analyzer)
    val updateStrength = if (isUserKeep) {
      min(0.1d * (rank.toDouble + 3.0d), 0.7d)
    } else {
      0.20d
    }
    lru.put(hash, uriId.id, updateStrength)
  }

  def getBoosts(userId: Id[User], query: String, maxBoost: Float) = {
    val hash = QueryHash(userId, query, analyzer)
    val likeliness = lru.get(hash)
    new ResultClickBoosts {
      def apply(value: Long) = 1.0f +  (maxBoost - 1.0f) * likeliness(value)
    }
  }
}
