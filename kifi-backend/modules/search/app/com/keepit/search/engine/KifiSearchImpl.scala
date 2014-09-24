package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search._
import com.keepit.search.engine.result.{ KifiShardResult, KifiResultCollector }
import com.keepit.search.engine.result.KifiResultCollector._
import org.apache.lucene.search.Query
import org.apache.lucene.search.Explanation
import scala.math._
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import com.keepit.search.tracker.ClickedURI
import com.keepit.search.tracker.ResultClickBoosts

class KifiSearchImpl(
    userId: Id[User],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig,
    engineBuilder: QueryEngineBuilder,
    articleSearcher: Searcher,
    keepSearcher: Searcher,
    friendIdsFuture: Future[Set[Long]],
    libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    clickBoostsFuture: Future[ResultClickBoosts],
    clickHistoryFuture: Future[MultiHashFilter[ClickedURI]],
    monitoredAwait: MonitoredAwait,
    timeLogs: SearchTimeLogs) extends KifiSearch(articleSearcher, keepSearcher, timeLogs) with Logging {

  private[this] val isInitialSearch = filter.idFilter.isEmpty

  // get config params
  private[this] val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
  private[this] val dampingHalfDecayFriends = config.asFloat("dampingHalfDecayFriends")
  private[this] val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
  private[this] val minMyBookmarks = config.asInt("minMyBookmarks")
  private[this] val myBookmarkBoost = config.asFloat("myBookmarkBoost")
  private[this] val usefulPageBoost = config.asFloat("usefulPageBoost")
  private[this] val percentMatch = config.asFloat("percentMatch")

  def executeTextSearch(maxTextHitsPerCategory: Int): (HitQueue, HitQueue, HitQueue) = {

    val engine = engineBuilder.build()
    debugLog("engine created")

    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, userId.id, friendIdsFuture, libraryIdsFuture, filter, config, monitoredAwait)
    val numRecs1 = engine.execute(keepScoreSource)
    debugLog(s"UriFromKeepsScoreVectorSource executed recs=$numRecs1")

    val articleScoreSource = new UriFromArticlesScoreVectorSource(articleSearcher, filter)
    val numRec2 = engine.execute(articleScoreSource)
    debugLog(s"UriFromArticlesScoreVectorSource executed recs=${numRec2 - numRecs1}")

    val clickBoosts = monitoredAwait.result(clickBoostsFuture, 5 seconds, s"getting clickBoosts for user Id $userId")
    timeLogs.clickBoost()

    if (debugFlags != 0) {
      if ((debugFlags & DebugOption.Trace.flag) != 0) engine.trace(debugTracedIds, this)
      if ((debugFlags & DebugOption.Library.flag) != 0) keepScoreSource.listLibraries(this)
    }

    val collector = new KifiResultCollector(clickBoosts, maxTextHitsPerCategory, percentMatch / 100.0f)
    debugLog("KifiResultCollector created")
    engine.join(collector)
    debugLog("engine joined")

    collector.getResults()
  }

  def execute(): KifiShardResult = {
    val (myHits, friendsHits, othersHits) = executeTextSearch(maxTextHitsPerCategory = numHitsToReturn * 5)

    val myTotal = myHits.totalHits
    val friendsTotal = friendsHits.totalHits

    val hits = createQueue(numHitsToReturn)

    // compute high score excluding others (an orphan uri sometimes makes results disappear)
    val highScore = {
      var highScore = max(myHits.highScore, friendsHits.highScore)
      if (highScore > 0.0f) highScore else max(othersHits.highScore, highScore)
    }

    val usefulPages = if (clickHistoryFuture.isCompleted) Await.result(clickHistoryFuture, 0 millisecond) else MultiHashFilter.emptyFilter[ClickedURI]

    if (myHits.size > 0 && filter.includeMine) {
      myHits.toRankedIterator.foreach {
        case (hit, rank) =>
          hit.score = hit.score * myBookmarkBoost * (if (usefulPages.mayContain(hit.id, 2)) usefulPageBoost else 1.0f)
          hit.normalizedScore = (hit.score / highScore) * KifiSearch.dampFunc(rank, dampingHalfDecayMine)
          hits.insert(hit)
      }
    }

    if (friendsHits.size > 0 && filter.includeFriends) {
      val queue = createQueue(numHitsToReturn - min(minMyBookmarks, hits.size))
      hits.discharge(hits.size - minMyBookmarks).foreach { h => queue.insert(h) }

      friendsHits.toRankedIterator.foreach {
        case (hit, rank) =>
          hit.score = hit.score * (if ((hit.visibility & Visibility.MEMBER) != 0) myBookmarkBoost else 1.0f) * (if (usefulPages.mayContain(hit.id, 2)) usefulPageBoost else 1.0f)
          hit.normalizedScore = (hit.score / highScore) * KifiSearch.dampFunc(rank, dampingHalfDecayFriends)
          queue.insert(hit)
      }
      queue.foreach { h => hits.insert(h) }
    }

    val noFriendlyHits = (hits.size == 0)

    var othersHighScore = -1.0f
    var othersTotal = othersHits.totalHits
    if (hits.size < numHitsToReturn && othersHits.size > 0 && filter.includeOthers) {
      val queue = createQueue(numHitsToReturn - hits.size)
      var othersNorm = Float.NaN
      var rank = 0 // compute the rank on the fly (there may be hits not kept public)
      othersHits.toSortedList.forall { hit =>
        if (isDiscoverable(hit.id)) {
          if (rank == 0) {
            // this is the first discoverable hit from others. compute the high score.
            othersHighScore = hit.score
            othersNorm = max(highScore, hit.score) * 1.1f // discount others hit
          }
          hit.score = hit.score * (if (usefulPages.mayContain(hit.id, 2)) usefulPageBoost else 1.0f)
          hit.normalizedScore = (hit.score / othersNorm) * KifiSearch.dampFunc(rank, dampingHalfDecayOthers)
          queue.insert(hit)
          rank += 1
        } else {
          othersTotal -= 1
        }
        hits.size < numHitsToReturn // until we fill up the queue
      }
      queue.foreach { h => hits.insert(h) }
    }

    val show = if (filter.isDefault && isInitialSearch && noFriendlyHits) false else (highScore > 0.6f || othersHighScore > 0.8f)

    timeLogs.processHits()
    timeLogs.done()
    timing()

    debugLog(s"myTotal=$myTotal friendsTotal=$friendsTotal othersTotal=$othersTotal show=$show")

    KifiShardResult(hits.toSortedList.map(h => toKifiShardHit(h)), myTotal, friendsTotal, othersTotal, show)
  }

  def explain(uriId: Id[NormalizedURI]): Option[(Query, Explanation)] = {
    throw new UnsupportedOperationException("explanation is not supported yet")
  }
}
