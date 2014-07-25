package com.keepit.common.cache

import scala.collection.concurrent.{ TrieMap => ConcurrentMap }

import java.util.concurrent.atomic.AtomicInteger

import com.google.inject.Singleton

@Singleton
class GlobalCacheStatistics() {
  private[cache] val hitsMap = ConcurrentMap[String, AtomicInteger]()
  private[cache] val missesMap = ConcurrentMap[String, AtomicInteger]()
  private[cache] val setsMap = ConcurrentMap[String, AtomicInteger]()

  def getStatistics: Seq[(String, Int, Int, Int)] = {
    val keys = (hitsMap.keySet ++ missesMap.keySet ++ setsMap.keySet).toSeq.sorted
    keys map { key =>
      (key, getCount(key, hitsMap), getCount(key, missesMap), getCount(key, setsMap))
    }
  }

  /**
   * @return the 100 * #misses/(#hits + #misses) Ratio per key that exist in the misses map
   */
  def missRatios(minSample: Int, minRatio: Int, cacheName: String = MemcachedCache.name): Seq[(String, Long)] =
    missesMap.keySet.toSeq.filter(_.startsWith(cacheName)) map { key =>
      getCount(key, missesMap) match {
        case missesInt if missesInt >= minSample =>
          val (misses, hits) = (missesInt.toDouble, getCount(key, hitsMap).toDouble)
          (100 * misses / (hits + misses)).round match {
            case ratio if ratio >= minRatio => Some(key.substring(cacheName.length + 1) -> ratio)
            case _ => None
          }
        case _ => None
      }
    } flatten

  private[cache] def getCount(key: String, m: ConcurrentMap[String, AtomicInteger]): Int = {
    m.get(key) match {
      case Some(counter) => counter.get()
      case _ => 0
    }
  }
}

