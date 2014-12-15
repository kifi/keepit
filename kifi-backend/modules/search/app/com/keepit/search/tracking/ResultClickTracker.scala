package com.keepit.search.tracking

import com.keepit.common.db.Id
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.engine.query.QueryHash
import com.keepit.model.NormalizedURI
import com.keepit.model.User
import scala.concurrent._
import scala.concurrent.duration._
import scala.math._
import scala.util.Random
import play.api.libs.concurrent.Execution.Implicits.defaultContext

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
      t(i) = sqrt(((i - 1).toDouble / (n - 1).toDouble)).toFloat // trying to stabilize the result when a few keeps are boosted at the same time
      i += 1
    }
    t
  }

  def add(userId: Id[User], query: String, uriId: Id[NormalizedURI], rank: Int, isUserKeep: Boolean, isDemo: Boolean = false): Unit = {
    val hash = QueryHash(userId, query, analyzer)
    val probe = lru.get(hash)
    val count = probe.count(uriId.id)

    if (count == 0 && !isDemo) {
      lru.put(hash, uriId.id, 0.01d)
    } else if (isUserKeep) {
      val updateStrength = min(0.1d * (rank.toDouble + 1.0d), 0.3)
      lru.put(hash, uriId.id, updateStrength)
    } else {
      lru.put(hash, uriId.id, 0.2d)
    }
  }

  def moderate(userId: Id[User], query: String): Unit = {
    val hash = QueryHash(userId, query, analyzer)
    lru.put(hash, rnd.nextLong(), 0.01d) // slowly making lru to forget by adding a random id
  }

  def getBoosts(userId: Id[User], query: String, maxBoost: Float): ResultClickBoosts = {
    Await.result(getBoostsFuture(userId, query, maxBoost), 10 seconds)
  }

  def getBoostsFuture(userId: Id[User], query: String, maxBoost: Float): Future[ResultClickBoosts] = {
    val hash = QueryHash(userId, query, analyzer)
    lru.getFuture(hash).map { probe =>
      new ResultClickBoosts {
        def apply(value: Long) = {
          val count = probe.count(value)
          if (count > 1) { 1.0f + maxBoost * boostFactor(count) } else { 1.0f }
        }
      }
    }
  }
}
