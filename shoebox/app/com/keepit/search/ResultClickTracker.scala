package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.{User, NormalizedURI}
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash
import java.io.File

object ResultClickTracker {

  def apply(dir: File, numHashFuncs: Int, syncEvery: Int) = {
    val file = new File(dir, "resultclicks.plru")
    // table size = 16M (physical size = 64MB + 4bytes)
    new ResultClickTracker(ProbablisticLRU(file, 0x1000000, numHashFuncs, syncEvery))
  }

  def apply(numHashFuncs: Int) = {
    new ResultClickTracker(ProbablisticLRU(1000, numHashFuncs, Int.MaxValue))
  }

  abstract class ResultClickBoosts {
    def apply(value: Long): Float
  }
}

class ResultClickTracker(lru: ProbablisticLRU) {
  private[this] val analyzer = DefaultAnalyzer.defaultAnalyzer

  def add(userId: Id[User], query: String, uriId: Id[NormalizedURI], isUserKeep: Boolean) = {
    val hash = QueryHash(userId, query, analyzer)
    val updateStrength = if (isUserKeep) 0.5d else 0.25d // user's keep => half the slots, others => quarter
    lru.put(hash, uriId.id, updateStrength)
  }

  def getBoosts(userId: Id[User], query: String, maxBoost: Float) = {
    val hash = QueryHash(userId, query, analyzer)
    val likeliness = lru.get(hash)
    new ResultClickTracker.ResultClickBoosts {
      def apply(value: Long) = 1.0f +  (maxBoost - 1.0f) * likeliness(value)
    }
  }
}
