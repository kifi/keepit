package com.keepit.search.engine

import com.keepit.common.db.Id
import com.keepit.model.{ Keep, User }
import com.keepit.search.{ Searcher, SearchConfig, SearchFilter }
import com.keepit.search.engine.result.{ HitQueue, LibraryShardHit, LibraryResultCollector, LibraryShardResult }
import com.keepit.common.logging.Logging
import scala.concurrent.Future
import com.keepit.common.akka.{ SafeFuture, MonitoredAwait }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.math._
import com.keepit.search.graph.keep.KeepRecord

class LibrarySearch(
    userId: Id[User],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig,
    engineBuilder: QueryEngineBuilder,
    librarySearcher: Searcher,
    keepSearcher: Searcher,
    friendIdsFuture: Future[Set[Long]],
    libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    monitoredAwait: MonitoredAwait,
    timeLogs: SearchTimeLogs) extends DebugOption with Logging {
  private[this] val percentMatch = config.asFloat("percentMatch")
  private[this] val myLibraryBoost = config.asFloat("myLibraryBoost")

  def execute(): LibraryShardResult = {
    val (myHits, friendsHits, othersHits) = executeTextSearch(maxTextHitsPerCategory = numHitsToReturn * 5)
    debugLog(s"myHits: ${myHits.totalHits}")
    debugLog(s"friendsHits: ${friendsHits.totalHits}")
    debugLog(s"othersHits: ${othersHits.totalHits}")
    val libraryShardResult = LibrarySearch.merge(myHits, friendsHits, othersHits, numHitsToReturn, filter, config)(keepId => KeepRecord.retrieve(keepSearcher, keepId).get)
    debugLog(s"libraryShardResult: ${libraryShardResult.hits.map(_.id).mkString(",")}")
    timeLogs.processHits()
    timeLogs.done()

    SafeFuture { timeLogs.send() }
    debugLog(timeLogs.toString)

    libraryShardResult
  }

  private def executeTextSearch(maxTextHitsPerCategory: Int): (HitQueue, HitQueue, HitQueue) = {
    val engine = engineBuilder.build()
    debugLog("library search engine created")

    val collector = new LibraryResultCollector(maxTextHitsPerCategory, myLibraryBoost, percentMatch / 100.0f)
    val keepScoreSource = new LibraryFromKeepsScoreVectorSource(keepSearcher, userId.id, friendIdsFuture, libraryIdsFuture, filter, config, monitoredAwait)
    val libraryScoreSource = new LibraryScoreVectorSource(librarySearcher, userId.id, friendIdsFuture, libraryIdsFuture, filter, config, monitoredAwait)

    if (debugFlags != 0) {
      engine.debug(this)
      keepScoreSource.debug(this)
    }

    engine.execute(collector, keepScoreSource, libraryScoreSource)

    timeLogs.search()

    collector.getResults()
  }

}

object LibrarySearch extends Logging {
  def merge(myHits: HitQueue, friendsHits: HitQueue, othersHits: HitQueue, maxHits: Int, filter: SearchFilter, config: SearchConfig)(keepsRecords: Id[Keep] => KeepRecord): LibraryShardResult = {

    val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
    val dampingHalfDecayFriends = config.asFloat("dampingHalfDecayFriends")
    val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
    val minMyLibraries = config.asInt("minMyLibraries")

    val isInitialSearch = filter.idFilter.isEmpty

    val myTotal = myHits.totalHits
    val friendsTotal = friendsHits.totalHits
    val othersTotal = othersHits.totalHits

    val hits = KifiSearch.createQueue(maxHits)

    // compute high score excluding others (an orphan uri sometimes makes results disappear)
    val highScore = {
      val highScore = max(myHits.highScore, friendsHits.highScore)
      if (highScore > 0.0f) highScore else max(othersHits.highScore, highScore)
    }

    if (myHits.size > 0 && filter.includeMine) {
      myHits.toRankedIterator.foreach {
        case (hit, rank) =>
          hit.normalizedScore = (hit.score / highScore) * KifiSearch.dampFunc(rank, dampingHalfDecayMine)
          hits.insert(hit)
      }
    }

    if (friendsHits.size > 0 && filter.includeFriends) {
      val queue = KifiSearch.createQueue(maxHits - min(minMyLibraries, hits.size))
      hits.discharge(hits.size - minMyLibraries).foreach { h => queue.insert(h) }

      friendsHits.toRankedIterator.foreach {
        case (hit, rank) =>
          hit.normalizedScore = (hit.score / highScore) * KifiSearch.dampFunc(rank, dampingHalfDecayFriends)
          queue.insert(hit)
      }
      queue.foreach { h => hits.insert(h) }
    }

    val noFriendlyHits = (hits.size == 0)

    val othersHighScore = othersHits.highScore
    if (hits.size < maxHits && othersHits.size > 0 && filter.includeOthers) {
      othersHits.toRankedIterator.take(maxHits - hits.size).foreach {
        case (hit, rank) =>
          val othersNorm = max(highScore, hit.score) * 1.1f // discount others hit
          hit.normalizedScore = (hit.score / othersNorm) * KifiSearch.dampFunc(rank, dampingHalfDecayOthers)
          hits.insert(hit)
      }
    }

    val show = if (filter.isDefault && isInitialSearch && noFriendlyHits) false else (highScore > 0.6f || othersHighScore > 0.8f)

    val libraryShardHits = hits.toSortedList.map { h =>
      val keep = if (h.secondaryId > 0) {
        val keepId = Id[Keep](h.secondaryId)
        val keepRecord = keepsRecords(keepId)
        Some((keepId, keepRecord))
      } else None
      LibraryShardHit(Id(h.id), h.score, h.visibility, keep)
    }

    LibraryShardResult(libraryShardHits, show)
  }

  def partition(libraryShardHits: Seq[LibraryShardHit]): (HitQueue, HitQueue, HitQueue, Map[Id[Keep], KeepRecord]) = {
    val maxHitsPerCategory = libraryShardHits.length
    val myHits = KifiSearch.createQueue(maxHitsPerCategory)
    val friendsHits = KifiSearch.createQueue(maxHitsPerCategory)
    val othersHits = KifiSearch.createQueue(maxHitsPerCategory)

    val keepRecords = libraryShardHits.map { hit =>
      val visibility = hit.visibility
      val relevantQueue = if ((visibility & Visibility.OWNER) != 0) {
        myHits
      } else if ((visibility & (Visibility.MEMBER | Visibility.NETWORK)) != 0) {
        friendsHits
      } else {
        othersHits
      }
      relevantQueue.insert(hit.id.id, hit.score, visibility, hit.keep.map(_._1.id).getOrElse(-1))
      hit.keep
    }.flatten.toMap
    (myHits, friendsHits, othersHits, keepRecords)
  }
}
