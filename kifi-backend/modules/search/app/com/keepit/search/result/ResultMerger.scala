package com.keepit.search.result

import com.keepit.search.SearchConfig
import com.keepit.search.util.MergeQueue
import scala.collection.mutable.ArrayBuffer
import scala.math._
import play.api.libs.json.JsNumber

class ResultMerger(enableTailCutting: Boolean, config: SearchConfig, isFinalMerge: Boolean) {
  // get config params
  private[this] val sharingBoostInNetwork = config.asFloat("sharingBoostInNetwork")
  private[this] val sharingBoostOutOfNetwork = config.asFloat("sharingBoostOutOfNetwork")
  private[this] val recencyBoost = config.asFloat("recencyBoost")
  private[this] val newContentBoost = config.asFloat("newContentBoost")
  private[this] val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
  private[this] val dampingHalfDecayFriends = config.asFloat("dampingHalfDecayFriends")
  private[this] val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
  private[this] val minMyBookmarks = config.asInt("minMyBookmarks")
  private[this] val myBookmarkBoost = config.asFloat("myBookmarkBoost")
  private[this] val usefulPageBoost = config.asFloat("usefulPageBoost")

  // tailCutting is set to low when a non-default filter is in use
  private[this] val tailCutting = if (enableTailCutting) config.asFloat("tailCutting") else 0.000f

  def merge(results: Seq[PartialSearchResult], maxHits: Int): PartialSearchResult = {
    val (myTotal, friendsTotal, othersTotal) = mergeTotals(results)
    val friendStats = mergeFriendStats(results)
    val hits = mergeHits(results, maxHits)
    val show = results.exists(_.show) // TODO: how to merge the show flag?

    val cutPoint = {
      if (!show) 0 else {
        hits.headOption.map { head =>
          val threshold = head.score * tailCutting
          hits.iterator.count(_.score > threshold)
        }.getOrElse(0)
      }
    }

    PartialSearchResult(
      hits,
      myTotal,
      friendsTotal,
      othersTotal,
      friendStats,
      show,
      cutPoint
    )
  }

  private def mergeHits(results: Seq[PartialSearchResult], maxHits: Int): Seq[DetailedSearchHit] = {
    val myHits = createQueue(maxHits * 5)
    val friendsHits = createQueue(maxHits * 5)
    val othersHits = createQueue(maxHits * 5)

    results.foreach { res =>
      res.hits.foreach { hit =>
        val scoring = hit.scoring
        (if (hit.isMyBookmark) myHits else if (hit.isFriendsBookmark) friendsHits else othersHits).insert(scoring.textScore, scoring, hit)
      }
    }

    // compute high score excluding others (an orphan uri sometimes makes results disappear)
    // and others high score (used for tailcutting of others hits)
    val (highScore, othersHighScore) = {
      var highScore = max(myHits.highScore, friendsHits.highScore)
      val othersHighScore = max(othersHits.highScore, highScore)
      if (highScore < 0.0f) highScore = othersHighScore
      (highScore, othersHighScore)
    }

    val threshold = highScore * tailCutting

    val hits = createQueue(maxHits)
    if (myHits.size > 0) {
      myHits.toRankedIterator.forall {
        case (hit, rank) =>
          val scoring = hit.scoring
          val score = hit.score * dampFunc(rank, dampingHalfDecayMine) // damping the scores by rank
          if (score > (threshold * (1.0f - scoring.recencyScore))) {
            scoring.normalizedTextScore = (score / highScore)
            hits.insert(scoring.score(myBookmarkBoost, sharingBoostInNetwork, recencyBoost, usefulPageBoost), scoring, hit.hit)
            true
          } else {
            false
          }
      }
    }

    if (friendsHits.size > 0) {
      val queue = createQueue(maxHits - min(minMyBookmarks, hits.size))
      hits.discharge(hits.size - minMyBookmarks).foreach { h => queue.insert(h) }

      friendsHits.toRankedIterator.forall {
        case (hit, rank) =>
          val scoring = hit.scoring
          val score = hit.score * dampFunc(rank, dampingHalfDecayFriends) // damping the scores by rank
          if (score > threshold) {
            scoring.normalizedTextScore = (score / highScore)
            queue.insert(scoring.score(1.0f, sharingBoostInNetwork, newContentBoost, usefulPageBoost), scoring, hit.hit)
            true
          } else {
            false
          }
      }
      queue.foreach { h => hits.insert(h) }
    }

    if (hits.size < maxHits && othersHits.size > 0) {
      val othersThreshold = othersHighScore * tailCutting
      val othersNorm = max(highScore, othersHighScore)
      val queue = createQueue(maxHits - hits.size)
      othersHits.toRankedIterator.forall {
        case (hit, rank) =>
          val scoring = hit.scoring
          val score = hit.score * dampFunc(rank, dampingHalfDecayOthers) // damping the scores by rank
          if (score > othersThreshold) {
            scoring.normalizedTextScore = (score / othersNorm)
            queue.insert(scoring.score(1.0f, sharingBoostOutOfNetwork, 0.0f, usefulPageBoost), scoring, hit.hit)
            true
          } else {
            false
          }
      }
      queue.foreach { h => hits.insert(h) }
    }

    val hitList = hits.toSortedList
    if (isFinalMerge) {
      hitList.map { hit =>
        hit.hit = hit.hit.set("score", JsNumber(hit.score.toDouble))
        hit.hit
      }
    } else {
      hitList.map { hit => hit.hit }
    }
  }

  @inline private def createQueue(maxHits: Int) = new MergeQueue[DetailedSearchHit](maxHits)
  @inline private[this] def dampFunc(rank: Int, halfDecay: Double) = (1.0d / (1.0d + pow(rank.toDouble / halfDecay, 3.0d))).toFloat

  private def mergeTotals(results: Seq[PartialSearchResult]): (Int, Int, Int) = {
    var myTotal = 0
    var friendsTotal = 0
    var othersTotal = 0
    results.foreach { res =>
      myTotal += res.myTotal
      friendsTotal += res.friendsTotal
      othersTotal += res.othersTotal
    }
    (myTotal, friendsTotal, othersTotal)
  }

  private def mergeFriendStats(results: Seq[PartialSearchResult]): FriendStats = {
    val jsons = results.map(_.json \ "friendStats")

    val idBuf = new ArrayBuffer[Long]
    jsons.foreach { json => idBuf ++= (json \ "ids").as[Seq[Long]] }

    val ids = idBuf.toSet[Long].toArray
    val friendStats = FriendStats(ids, new Array[Float](ids.length))

    var i = 0
    jsons.foreach { json =>
      val scores = (json \ "scores").as[Seq[Float]]
      scores.foreach { sc =>
        friendStats.add(idBuf(i), sc)
        i += 1
      }
    }
    friendStats
  }

}
