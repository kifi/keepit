package com.keepit.common.cache

import scala.collection.concurrent.{ TrieMap => ConcurrentMap }
import org.specs2.mutable.Specification
import java.util.concurrent.atomic.AtomicInteger

class GlobalCacheStatisticsTest extends Specification {

  private def incrCount(key: String, m: ConcurrentMap[String, AtomicInteger]) {
    m.getOrElseUpdate(key, new AtomicInteger(0)).incrementAndGet()
  }

  "GlobalCacheStatistics" should {
    "record miss ratios has nothing" in {
      val stats = new GlobalCacheStatistics()
      stats.missRatios(minSample = 10, minRatio = 50).isEmpty === true
      0 to 10 foreach { _ => incrCount("aaa", stats.hitsMap) }
      stats.missRatios(minSample = 2, minRatio = 50).isEmpty === true
      0 to 10 foreach { _ => incrCount("aaa", stats.missesMap) }
      stats.missRatios(minSample = 2, minRatio = 80).isEmpty === true
      stats.missRatios(minSample = 14, minRatio = 30).isEmpty === true
      stats.missRatios(minSample = 5, minRatio = 20).head === ("aaa", 50)

      0 to 140 foreach { _ => incrCount("bbb", stats.hitsMap) }
      0 to 60 foreach { _ => incrCount("bbb", stats.missesMap) }
      stats.missRatios(minSample = 2, minRatio = 40) === Seq(("aaa", 50))

      0 to 100 foreach { _ => incrCount("ccc", stats.hitsMap) }
      0 to 300 foreach { _ => incrCount("ccc", stats.missesMap) }
      stats.missRatios(minSample = 2, minRatio = 40) === Seq(("aaa", 50), ("ccc", 75))
      stats.missRatios(minSample = 40, minRatio = 40) === Seq(("ccc", 75))
      stats.missRatios(minSample = 40, minRatio = 90).isEmpty === true

      stats.missRatios(minSample = 100000, minRatio = 5).isEmpty === true
      stats.missRatios(minSample = 1, minRatio = 1) === Seq(("aaa", 50), ("ccc", 75), ("bbb", 30))
    }
  }

}
