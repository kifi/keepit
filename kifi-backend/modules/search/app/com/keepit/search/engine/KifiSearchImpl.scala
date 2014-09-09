package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search._
import com.keepit.search.engine.result.{ KifiShardResult, KifiResultCollector }
import com.keepit.search.engine.result.KifiResultCollector._
import org.apache.lucene.search.Query
import org.apache.lucene.search.Explanation
import scala.math._
import scala.concurrent.{ Future, Promise }
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
    libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long])],
    clickBoostsFuture: Future[ResultClickBoosts],
    clickHistoryFuture: Future[MultiHashFilter[ClickedURI]],
    monitoredAwait: MonitoredAwait,
    timeLogs: SearchTimeLogs) extends KifiSearch(articleSearcher, keepSearcher, timeLogs) with Logging {

  private[this] val currentTime = currentDateTime.getMillis()
  private[this] val idFilter = filter.idFilter
  private[this] val isInitialSearch = idFilter.isEmpty

  // get config params
  private[this] val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
  private[this] val dampingHalfDecayFriends = config.asFloat("dampingHalfDecayFriends")
  private[this] val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
  private[this] val minMyBookmarks = config.asInt("minMyBookmarks")
  private[this] val myBookmarkBoost = config.asFloat("myBookmarkBoost")
  private[this] val usefulPageBoost = config.asFloat("usefulPageBoost")
  private[this] val forbidEmptyFriendlyHits = config.asBoolean("forbidEmptyFriendlyHits")
  private[this] val percentMatch = config.asFloat("percentMatch")

  def executeTextSearch(maxTextHitsPerCategory: Int): (HitQueue, HitQueue, HitQueue) = {

    val engine = engineBuilder.build()
    log.info(s"NE: engine created (${System.currentTimeMillis - currentTime})")
    log.info(s"NE: query ${engine.getQuery().toString()})")

    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, userId.id, friendIdsFuture, libraryIdsFuture, filter, config, monitoredAwait)
    log.info(s"NE: UriFromKeepsScoreVectorSource created (${System.currentTimeMillis - currentTime})")
    val numRecs1 = engine.execute(keepScoreSource)
    log.info(s"NE: UriFromKeepsScoreVectorSource executed recs=$numRecs1 (${System.currentTimeMillis - currentTime})")

    val articleScoreSource = new UriFromArticlesScoreVectorSource(articleSearcher, filter)
    log.info(s"NE: UriFromArticlesScoreVectorSource created (${System.currentTimeMillis - currentTime})")
    val numRec2 = engine.execute(articleScoreSource)
    log.info(s"NE: UriFromArticlesScoreVectorSource executed recs=${numRec2 - numRecs1} (${System.currentTimeMillis - currentTime})")

    val tClickBoosts = currentDateTime.getMillis()
    val clickBoosts = monitoredAwait.result(clickBoostsFuture, 5 seconds, s"getting clickBoosts for user Id $userId")
    timeLogs.getClickBoost = currentDateTime.getMillis() - tClickBoosts
    log.info(s"NE: clickBoosts created (${System.currentTimeMillis - currentTime})")

    val collector = new KifiResultCollector(clickBoosts, maxTextHitsPerCategory, percentMatch / 100.0f)
    log.info(s"NE: KifiResultCollector created (${System.currentTimeMillis - currentTime})")
    engine.join(collector)
    log.info(s"NE: KifiResultCollector joined (${System.currentTimeMillis - currentTime})")

    collector.logCount()

    collector.getResults()
  }

  def execute(): KifiShardResult = {
    val now = currentDateTime
    val (myHits, friendsHits, othersHits) = executeTextSearch(maxTextHitsPerCategory = numHitsToReturn * 5)

    val tProcessHits = currentDateTime.getMillis()

    val myTotal = myHits.totalHits
    val friendsTotal = friendsHits.totalHits

    val hits = createQueue(numHitsToReturn)

    // compute high score excluding others (an orphan uri sometimes makes results disappear)
    val highScore = {
      var highScore = max(myHits.highScore, friendsHits.highScore)
      if (highScore > 0.0f) highScore else max(othersHits.highScore, highScore)
    }

    val usefulPages = monitoredAwait.result(clickHistoryFuture, 40 millisecond, s"getting click history for user $userId", MultiHashFilter.emptyFilter[ClickedURI])

    if (myHits.size > 0 && filter.includeMine) {
      myHits.toRankedIterator.foreach {
        case (hit, rank) =>
          val boost = myBookmarkBoost * (if (usefulPages.mayContain(hit.id, 2)) usefulPageBoost else 1.0f)
          hit.score = hit.score * dampFunc(rank, dampingHalfDecayMine) * boost
          hit.normalizedScore = hit.score / highScore
          hits.insert(hit)
      }
    }

    if (friendsHits.size > 0 && filter.includeFriends) {
      val queue = createQueue(numHitsToReturn - min(minMyBookmarks, hits.size))
      hits.discharge(hits.size - minMyBookmarks).foreach { h => queue.insert(h) }

      friendsHits.toRankedIterator.foreach {
        case (hit, rank) =>
          val boost = (if ((hit.visibility & Visibility.MEMBER) != 0) myBookmarkBoost else 1.0f) * (if (usefulPages.mayContain(hit.id, 2)) usefulPageBoost else 1.0f)
          hit.score = hit.score * dampFunc(rank, dampingHalfDecayFriends) * boost
          hit.normalizedScore = hit.score / highScore
          queue.insert(hit)
      }
      queue.foreach { h => hits.insert(h) }
    }

    val noFriendlyHits = (hits.size == 0)

    var othersHighScore = -1.0f
    var othersTotal = othersHits.size
    if (hits.size < numHitsToReturn && othersHits.size > 0 && filter.includeOthers &&
      (!forbidEmptyFriendlyHits || hits.size == 0 || !filter.isDefault || !isInitialSearch)) {
      val queue = createQueue(numHitsToReturn - hits.size)
      var othersNorm = Float.NaN
      var rank = 0 // compute the rank on the fly (there may be hits not kept public)
      othersHits.toSortedList.forall { hit =>
        if (isDiscoverable(hit.id)) {
          if (rank == 0) {
            // this is the first discoverable hit from others. compute the high score.
            othersHighScore = hit.score
            othersNorm = max(highScore, hit.score)
          }
          hit.score = hit.score * dampFunc(rank, dampingHalfDecayOthers) * (if (usefulPages.mayContain(hit.id, 2)) usefulPageBoost else 1.0f)
          hit.normalizedScore = hit.score / othersNorm
          queue.insert(hit)
          rank += 1
        } else {
          othersTotal -= 1
        }
        hits.size < numHitsToReturn // until we fill up the queue
      }
      queue.foreach { h => hits.insert(h) }
    }

    val show = if (filter.isDefault && isInitialSearch && (noFriendlyHits && forbidEmptyFriendlyHits)) {
      false
    } else {
      highScore > 0.6f || othersHighScore > 0.8f
    }

    timeLogs.processHits = currentDateTime.getMillis() - tProcessHits
    timeLogs.total = currentDateTime.getMillis() - now.getMillis()
    timing()

    log.info(s"NE: myTotal=$myTotal friendsTotal=$friendsTotal othersTotal=$othersTotal show=$show")

    KifiShardResult(hits.toSortedList.map(h => toKifiShardHit(h)), myTotal, friendsTotal, othersTotal, show)
  }

  def explain(uriId: Id[NormalizedURI]): Option[(Query, Explanation)] = {
    throw new UnsupportedOperationException("explanation is not supported yet")
  }
}
