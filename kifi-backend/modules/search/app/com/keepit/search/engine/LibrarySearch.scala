package com.keepit.search.engine

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.{ Searcher, SearchConfig, SearchFilter }
import com.keepit.search.engine.result.KifiResultCollector.HitQueue
import com.keepit.search.engine.result.{ LibraryShardHit, LibraryResultCollector, LibraryShardResult }
import com.keepit.common.logging.Logging
import scala.concurrent.Future
import com.keepit.common.akka.{ SafeFuture, MonitoredAwait }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.math._

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
  private[this] val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
  private[this] val dampingHalfDecayFriends = config.asFloat("dampingHalfDecayFriends")
  private[this] val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
  private[this] val minMyLibraries = config.asInt("minMyLibraries")
  private[this] val myLibraryBoost = config.asFloat("myLibraryBoost")
  private[this] val percentMatch = config.asFloat("percentMatch")

  private[this] val isInitialSearch = filter.idFilter.isEmpty

  def execute(): LibraryShardResult = {
    val (myHits, friendsHits, othersHits) = executeTextSearch(maxTextHitsPerCategory = numHitsToReturn * 5)

    val myTotal = myHits.totalHits
    val friendsTotal = friendsHits.totalHits
    val othersTotal = othersHits.totalHits

    val hits = KifiSearch.createQueue(numHitsToReturn)

    // compute high score excluding others (an orphan uri sometimes makes results disappear)
    val highScore = {
      val highScore = max(myHits.highScore, friendsHits.highScore)
      if (highScore > 0.0f) highScore else max(othersHits.highScore, highScore)
    }

    if (myHits.size > 0 && filter.includeMine) {
      myHits.toRankedIterator.foreach {
        case (hit, rank) =>
          hit.score = hit.score * myLibraryBoost
          hit.normalizedScore = (hit.score / highScore) * KifiSearch.dampFunc(rank, dampingHalfDecayMine)
          hits.insert(hit)
      }
    }

    if (friendsHits.size > 0 && filter.includeFriends) {
      val queue = KifiSearch.createQueue(numHitsToReturn - min(minMyLibraries, hits.size))
      hits.discharge(hits.size - minMyLibraries).foreach { h => queue.insert(h) }

      friendsHits.toRankedIterator.foreach {
        case (hit, rank) =>
          hit.score = hit.score * (if ((hit.visibility & Visibility.MEMBER) != 0) myLibraryBoost else 1.0f)
          hit.normalizedScore = (hit.score / highScore) * KifiSearch.dampFunc(rank, dampingHalfDecayFriends)
          queue.insert(hit)
      }
      queue.foreach { h => hits.insert(h) }
    }

    val noFriendlyHits = (hits.size == 0)

    val othersHighScore = othersHits.highScore
    if (hits.size < numHitsToReturn && othersHits.size > 0 && filter.includeOthers) {

      val queue = KifiSearch.createQueue(numHitsToReturn - hits.size)
      hits.discharge(hits.size - minMyLibraries).foreach { h => queue.insert(h) }

      othersHits.toRankedIterator.take(numHitsToReturn - hits.size).foreach {
        case (hit, rank) =>
          val othersNorm = max(highScore, hit.score) * 1.1f // discount others hit
          hit.normalizedScore = (hit.score / othersNorm) * KifiSearch.dampFunc(rank, dampingHalfDecayOthers)
          queue.insert(hit)
      }
      queue.foreach { h => hits.insert(h) }
    }

    val show = if (filter.isDefault && isInitialSearch && noFriendlyHits) false else (highScore > 0.6f || othersHighScore > 0.8f)

    timeLogs.processHits()
    timeLogs.done()

    //todo(Léo): DRY with KifiSearch
    SafeFuture {
      timeLogs.send()
      if ((debugFlags & DebugOption.Timing.flag) != 0) log.info(timeLogs.toString)
    }

    log.info(s"NE: myTotal=$myTotal friendsTotal=$friendsTotal othersTotal=$othersTotal show=$show")

    val libraryShardHits = hits.toSortedList.map { h =>
      LibraryShardHit(Id(h.id), h.score, h.visibility, if (h.secondaryId > 0) Some(Id(h.secondaryId)) else None)
    }
    LibraryShardResult(libraryShardHits)
  }

  private def executeTextSearch(maxTextHitsPerCategory: Int): (HitQueue, HitQueue, HitQueue) = {
    val engine = engineBuilder.build()
    log.info(s"NE: engine created (${timeLogs.elapsed()})")

    val keepScoreSource = new LibraryFromKeepsScoreVectorSource(keepSearcher, userId.id, friendIdsFuture, libraryIdsFuture, filter, config, monitoredAwait)
    val numRecs1 = engine.execute(keepScoreSource)
    log.info(s"NE: LibraryFromKeepsScoreVectorSource executed recs=$numRecs1 (${timeLogs.elapsed()})")

    val libraryScoreSource = new LibraryScoreVectorSource(librarySearcher, userId.id, friendIdsFuture, libraryIdsFuture, filter, config, monitoredAwait)
    val numRec2 = engine.execute(libraryScoreSource)
    log.info(s"NE: LibraryScoreVectorSource executed recs=${numRec2 - numRecs1} (${timeLogs.elapsed()})")

    if (debugFlags != 0) {
      if ((debugFlags & DebugOption.Trace.flag) != 0) engine.trace(debugTracedIds)
      if ((debugFlags & DebugOption.Library.flag) != 0) listLibraries(keepScoreSource)
    }

    val collector = new LibraryResultCollector(maxTextHitsPerCategory, percentMatch / 100.0f)
    log.info(s"NE: LibraryResultCollector created (${timeLogs.elapsed()})")
    engine.join(collector)
    log.info(s"NE: LibraryResultCollector joined (${timeLogs.elapsed()})")

    collector.getResults()
  }

}
