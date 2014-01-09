package com.keepit.search.tracker

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

  private[this] val boostFactor: Array[Float] = {
    val n = lru.numHashFuncs
    val t = new Array[Float](n + 1)
    var i = 1
    while (i <= n) {
      t(i) = sqrt(((i - 1).toDouble/(n - 1).toDouble)).toFloat // trying to stabilize the result when a few keeps are boosted at the same time
      i += 1
    }
    t
  }

  def add(userId: Id[User], query: String, uriId: Id[NormalizedURI], rank: Int, isUserKeep: Boolean, isDemo: Boolean = false): Unit = {
    val hash = QueryHash(userId, query, analyzer)
    val probe = lru.get(hash, true)
    val norm = lru.numHashFuncs.toDouble
    val count = probe.count(uriId.id)

    if (count == 0 && !isDemo) {
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
      def apply(value: Long) = {
        val count = probe.count(value)
        if (count > 1) { 1.0f + maxBoost * boostFactor(count) } else { 1.0f }
      }
    }
  }
}
