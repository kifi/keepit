package com.keepit.search.engine.result

import com.keepit.search.SearchConfig
import com.keepit.search.engine.Visibility
import com.keepit.search.util.HitQueue
import scala.math._
import play.api.libs.json.JsNumber

class KifiResultMerger(enableTailCutting: Boolean, config: SearchConfig) {
  // get config params
  private[this] val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
  private[this] val dampingHalfDecayFriends = config.asFloat("dampingHalfDecayFriends")
  private[this] val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
  private[this] val minMyBookmarks = config.asInt("minMyBookmarks")
  private[this] val myBookmarkBoost = config.asFloat("myBookmarkBoost")
  private[this] val usefulPageBoost = config.asFloat("usefulPageBoost")
  private[this] val forbidEmptyFriendlyHits = config.asBoolean("forbidEmptyFriendlyHits")

  // tailCutting is set to low when a non-default filter is in use
  private[this] val tailCutting = if (enableTailCutting) config.asFloat("tailCutting") else 0.000f

  def merge(results: Seq[KifiShardResult], maxHits: Int): KifiShardResult = {
    val (myTotal, friendsTotal) = mergeTotals(results)
    val hits = mergeHits(results, maxHits)
    val show = results.exists(_.show)

    val cutPoint = {
      hits.headOption.map { head =>
        val threshold = head.score * tailCutting
        hits.iterator.count(_.score > threshold)
      }.getOrElse(0)
    }

    KifiShardResult(
      hits,
      myTotal,
      friendsTotal,
      show,
      cutPoint
    )
  }

  private def mergeHits(results: Seq[KifiShardResult], maxHits: Int): Seq[KifiShardHit] = {
    val myHits = createQueue(maxHits * 5)
    val friendsHits = createQueue(maxHits * 5)
    val othersHits = createQueue(maxHits * 5)

    results.foreach { res =>
      res.hits.foreach { hit =>
        val queue = hit.visibility match {
          case Visibility.MEMBER => myHits
          case Visibility.NETWORK => friendsHits
          case _ => othersHits
        }
        queue.insert(hit.score, null, hit)
      }
    }

    // compute high score excluding others (an orphan uri sometimes makes results disappear)
    // and others high score (used for tailcutting of others hits)
    val highScore = {
      var highScore = max(myHits.highScore, friendsHits.highScore)
      if (highScore > 0.0f) highScore else max(othersHits.highScore, highScore)
    }

    val hits = createQueue(maxHits)
    if (myHits.size > 0) {
      myHits.toRankedIterator.foreach {
        case (hit, rank) =>
          var score = hit.score * dampFunc(rank, dampingHalfDecayMine) // damping the scores by rank
          hits.insert(score / highScore, null, hit.hit)
      }
    }

    if (friendsHits.size > 0) {
      val queue = createQueue(maxHits - min(minMyBookmarks, hits.size))
      hits.discharge(hits.size - minMyBookmarks).foreach { h => queue.insert(h) }

      friendsHits.toRankedIterator.foreach {
        case (hit, rank) =>
          val score = hit.score * dampFunc(rank, dampingHalfDecayFriends) // damping the scores by rank
          queue.insert(score / highScore, null, hit.hit)
      }
      queue.foreach { h => hits.insert(h) }
    }

    if (hits.size < maxHits && othersHits.size > 0) {
      val othersNorm = max(highScore, othersHits.highScore)
      val queue = createQueue(maxHits - hits.size)
      othersHits.toRankedIterator.foreach {
        case (hit, rank) =>
          val score = hit.score * dampFunc(rank, dampingHalfDecayOthers) // damping the scores by rank
          queue.insert(score / othersNorm, null, hit.hit)
      }
      queue.foreach { h => hits.insert(h) }
    }

    hits.toSortedList.map { hit =>
      hit.hit.set("score", JsNumber(hit.score.toDouble))
    }
  }

  @inline private def createQueue(maxHits: Int) = new HitQueue[KifiShardHit](maxHits)
  @inline private[this] def dampFunc(rank: Int, halfDecay: Double) = (1.0d / (1.0d + pow(rank.toDouble / halfDecay, 3.0d))).toFloat

  private def mergeTotals(results: Seq[KifiShardResult]): (Int, Int) = {
    var myTotal = 0
    var friendsTotal = 0
    results.foreach { res =>
      myTotal += res.myTotal
      friendsTotal += res.friendsTotal
    }
    (myTotal, friendsTotal)
  }
}
