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
    val hash = QueryHash(userId, query, analyzer)
    val probe = lru.get(hash, false) // use master
    val norm = probe.norm.toDouble
    val count = probe.count(uriId.id)

    if (count == 0) {
      lru.put(hash, uriId.id, 0.01d)
    } else if (isUserKeep) {
      val updateStrength = min(min(0.1d * (rank.toDouble + 3.0d), (count * 2).toDouble/norm.toDouble), 0.7)
      lru.put(hash, uriId.id, updateStrength)
    } else {
      lru.put(hash, uriId.id, 0.2d)
    }
  }

  def moderate(userId: Id[User], query: String): Unit = {
    val hash = QueryHash(userId, query, analyzer)
    lru.put(hash, rnd.nextLong(), 0.01d) // slowly making lru to forget by adding a random id
  }

  def getBoosts(userId: Id[User], query: String, maxBoost: Float, useSlaveAsPrimary: Boolean = false): ResultClickBoosts = {
    val hash = QueryHash(userId, query, analyzer)
    val probe = lru.get(hash, useSlaveAsPrimary)
    new ResultClickBoosts {
      private[this] val norm: Float = probe.norm
      def apply(value: Long) = {
        val count = probe.count(value)
        if (count > 1) { 1.0f +  (maxBoost - 1.0f) * count.toFloat/norm } else { 1.0f }
      }
    }
  }
}
