package com.keepit.common.cache

import scala.collection.concurrent.{ TrieMap => ConcurrentMap }

import java.util.concurrent.atomic.AtomicInteger

import com.google.inject.Singleton

@Singleton
class GlobalCacheStatistics() {
  private[cache] val hitsMap = ConcurrentMap[String, AtomicInteger]()
  private[cache] val missesMap = ConcurrentMap[String, AtomicInteger]()
  private[cache] val setsMap = ConcurrentMap[String, AtomicInteger]()

  def getStatistics: Seq[(String, Int, Int, Int, Int)] = {
    val keys = (hitsMap.keySet ++ missesMap.keySet ++ setsMap.keySet).toSeq.sorted
    keys map { key =>
      val (hits, misses, sets) = (hitCount(key), missCount(key), setCount(key))
      (key, hits, misses, sets, missRatio(misses = misses, hits = hits, sets = sets))
    }
  }

  /**
   * Best is to read the code to understand the function.
   * The logic is like this: If there are few #sets we don't care that much, but if the number of sets is
   * larger then number of misses then its probably a cache that has lots of random and constantly increasing number of values like the uri space.
   * In that case, we discount the number of misses by number of sets.
   */
  private def missRatio(hits: Int, misses: Int, sets: Int): Int =
    (100d * (misses - (sets - misses).max(0)).max(0) / (hits + misses).toDouble).round.toInt

  def missRatios(minSample: Int, minRatio: Int, cacheName: String): Seq[(String, Int)] =
    missesMap.keySet.toSeq.filter(_.startsWith(cacheName)) map { key =>
      getCount(key, missesMap) match {
        case misses if misses >= minSample =>
          val (hits, sets) = (hitCount(key), setCount(key))
          missRatio(misses = misses, hits = hits, sets = sets) match {
            case ratio if ratio >= minRatio => Some(key, ratio)
            case _ => None
          }
        case _ => None
      }
    } flatten

  def hitCount(key: String): Int = getCount(key, hitsMap)
  def missCount(key: String): Int = getCount(key, missesMap)
  def setCount(key: String): Int = getCount(key, setsMap)

  private[cache] def getCount(key: String, m: ConcurrentMap[String, AtomicInteger]): Int = {
    m.get(key) match {
      case Some(counter) => counter.get()
      case _ => 0
    }
  }
}

