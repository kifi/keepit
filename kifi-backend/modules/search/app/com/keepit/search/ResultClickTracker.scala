package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import scala.math._
import scala.util.Random


abstract class ResultClickBoosts {
  def apply(value: Long): Float
}

class ResultClickTracker(lru: ProbablisticLRU) {

  private[this] val rnd = new Random

  private[this] val analyzer = DefaultAnalyzer.defaultAnalyzer

  def add(userId: Id[User], query: String, uriId: Id[NormalizedURI], rank: Int, isUserKeep: Boolean): Unit = {
    val updateStrength = if (isUserKeep) {
      min(0.1d * (rank.toDouble + 3.0d), 0.7d)
    } else {
      0.20d
    }
    add(userId, query, uriId.id, updateStrength)
  }

  def moderate(userId: Id[User], query: String): Unit = {
    add(userId, query, rnd.nextLong(), 0.1d) // slowly making lru to forget by adding a random id
  }

  private[this] def add(userId: Id[User], query: String, uriId: Long, updateStrength: Double): Unit = {
    lru.put(QueryHash(userId, query, analyzer), uriId, updateStrength)
  }

  def getBoosts(userId: Id[User], query: String, maxBoost: Float, useSlaveAsPrimary: Boolean = false): ResultClickBoosts = {
    val hash = QueryHash(userId, query, analyzer)
    val likeliness = lru.get(hash, useSlaveAsPrimary)
    new ResultClickBoosts {
      def apply(value: Long) = 1.0f +  (maxBoost - 1.0f) * likeliness(value)
    }
  }
}
