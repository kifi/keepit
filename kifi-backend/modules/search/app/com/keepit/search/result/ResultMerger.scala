package com.keepit.search.result

import com.keepit.search.SearchConfig
import com.keepit.search.util.HitQueue
import org.apache.lucene.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer
import scala.math._
import play.api.libs.json.JsNumber

object ResultMerger {

  def merge(results: Seq[ShardSearchResult], maxHits: Int, enableTailCutting: Boolean, config: SearchConfig): MergedSearchResult = {
    if (results.size == 1) {
      val head = results.head
      MergedSearchResult(head.hits, head.myTotal, head.friendsTotal, head.othersTotal, head.friendStats, head.show, head.svVariance)
    } else {
      val (myTotal, friendsTotal, othersTotal) = mergeTotals(results)
      val friendStats = mergeFriendStats(results)
      val hits = mergeHits(results, maxHits, enableTailCutting, config)
      val show = results.exists(_.show) // TODO: how to merge the show flag?

      MergedSearchResult(
        hits,
        myTotal,
        friendsTotal,
        othersTotal,
        friendStats,
        show,
        -1.0f
      )
    }
  }

  private def mergeHits(results: Seq[ShardSearchResult], maxHits: Int, enableTailCutting: Boolean, config: SearchConfig): Seq[DetailedSearchHit] = {
    // get config params
    val sharingBoostInNetwork = config.asFloat("sharingBoostInNetwork")
    val sharingBoostOutOfNetwork = config.asFloat("sharingBoostOutOfNetwork")
    val recencyBoost = config.asFloat("recencyBoost")
    val newContentBoost = config.asFloat("newContentBoost")
    val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
    val dampingHalfDecayFriends = config.asFloat("dampingHalfDecayFriends")
    val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
    val minMyBookmarks = config.asInt("minMyBookmarks")
    val myBookmarkBoost = config.asFloat("myBookmarkBoost")
    val usefulPageBoost = config.asFloat("usefulPageBoost")

    // tailCutting is set to low when a non-default filter is in use
    val tailCutting = if (enableTailCutting) config.asFloat("tailCutting") else 0.000f

    val myHits = createQueue(maxHits * 5)
    val friendsHits = createQueue(maxHits * 5)
    val othersHits = createQueue(maxHits * 5)

    results.foreach{ res =>
      res.hits.foreach{ hit =>
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
      myHits.toRankedIterator.forall{ case (hit, rank) =>
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
      hits.discharge(hits.size - minMyBookmarks).foreach{ h => queue.insert(h) }

      friendsHits.toRankedIterator.forall{ case (hit, rank) =>
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
      queue.foreach{ h => hits.insert(h) }
    }

    var onlyContainsOthersHits = false

    if (hits.size < maxHits && othersHits.size > 0) {
      val othersThreshold = othersHighScore * tailCutting
      val othersNorm = max(highScore, othersHighScore)
      val queue = createQueue(maxHits - hits.size)
      if (hits.size == 0) onlyContainsOthersHits = true
      othersHits.toRankedIterator.forall{ case (hit, rank) =>
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
      queue.foreach{ h => hits.insert(h) }
    }

    val hitList = hits.toSortedList
    hitList.map{ hit =>
      hit.hit = hit.hit.set("score", JsNumber(hit.score))
      hit.hit
    }
  }

  @inline private def createQueue(maxHits: Int) = new HitQueue[DetailedSearchHit](maxHits)
  @inline private[this] def dampFunc(rank: Int, halfDecay: Double) = (1.0d / (1.0d + pow(rank.toDouble/halfDecay, 3.0d))).toFloat

  private def mergeTotals(results: Seq[ShardSearchResult]): (Int, Int, Int) = {
    var myTotal = 0
    var friendsTotal = 0
    var othersTotal = 0
    results.foreach{ res =>
      myTotal += res.myTotal
      friendsTotal += res.friendsTotal
      othersTotal += res.othersTotal
    }
    (myTotal, friendsTotal, othersTotal)
  }

  private def mergeFriendStats(results: Seq[ShardSearchResult]): FriendStats = {
    val jsons = results.map(_.json \ "friendStats")

    val idBuf = new ArrayBuffer[Long]
    jsons.foreach{ json => idBuf ++= (json \ "ids").as[Seq[Long]] }

    val scBuf = new ArrayBuffer[Float](idBuf.size)
    jsons.foreach{ json => scBuf ++= (json \ "scores").as[Seq[Float]] }

    val ids = idBuf.toSet.toArray
    val friendStats = FriendStats(ids, new Array[Float](ids.size))

    var i = 0
    jsons.foreach{ json =>
      val scores = (json \ "scores").as[Seq[Float]]
      scores.foreach{ sc =>
        friendStats.add(idBuf(i), sc)
        i += 1
      }
    }
    friendStats
  }

}
