package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search._
import com.keepit.search.engine.explain.{ ScoreDetailCollector, Explanation }
import com.keepit.search.engine.result._
import com.keepit.search.engine.result.KifiResultCollector._
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
    librarySearcher: Searcher,
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
  private[this] val sharingBoostInNetwork = config.asFloat("sharingBoostInNetwork")

  private[this] val clickBoostsProvider: () => ResultClickBoosts = { () =>
    val ret = monitoredAwait.result(clickBoostsFuture, 5 seconds, s"getting clickBoosts")
    timeLogs.clickBoost()
    ret
  }

  def executeTextSearch(maxTextHitsPerCategory: Int): (HitQueue, HitQueue, HitQueue) = {

    val engine = engineBuilder.build()
    debugLog("engine created")

    val collector: KifiResultCollector = if (engine.recencyOnly) {
      new KifiResultCollectorWithNoBoost(maxTextHitsPerCategory, percentMatch / 100.0f)
    } else {
      new KifiResultCollectorWithBoost(clickBoostsProvider, maxTextHitsPerCategory, percentMatch / 100.0f, sharingBoostInNetwork)
    }

    val libraryScoreSource = new UriFromLibraryScoreVectorSource(librarySearcher, keepSearcher, libraryIdsFuture, filter, config, monitoredAwait)
    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, userId.id, friendIdsFuture, libraryIdsFuture, filter, engine.recencyOnly, config, monitoredAwait)
    val articleScoreSource = new UriFromArticlesScoreVectorSource(articleSearcher, filter)

    if (debugFlags != 0) {
      engine.debug(this)
      libraryScoreSource.debug(this)
      keepScoreSource.debug(this)
      articleScoreSource.debug(this)
    }

    engine.execute(collector, libraryScoreSource, keepScoreSource, articleScoreSource)

    timeLogs.search()

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
          hits.insert(hit)
          rank += 1
        } else {
          othersTotal -= 1
        }
        hits.size < numHitsToReturn // until we fill up the queue
      }
    }

    val show = if (filter.isDefault && isInitialSearch && noFriendlyHits) false else (highScore > 0.6f || othersHighScore > 0.8f)

    timeLogs.processHits()
    timeLogs.done()
    timing()

    debugLog(s"myTotal=$myTotal friendsTotal=$friendsTotal othersTotal=$othersTotal show=$show")

    KifiShardResult(hits.toSortedList.map(h => toKifiShardHit(h)), myTotal, friendsTotal, othersTotal, show)
  }

  def explain(uriId: Id[NormalizedURI]): Explanation = {
    val engine = engineBuilder.build()
    val labels = engineBuilder.getQueryLabels()
    val query = engine.getQuery()
    val collector = if (engine.recencyOnly) {
      new ScoreDetailCollector(uriId.id, None, None)
    } else {
      new ScoreDetailCollector(uriId.id, Some(clickBoostsProvider), Some(sharingBoostInNetwork))
    }

    val libraryScoreSource = new UriFromLibraryScoreVectorSource(librarySearcher, keepSearcher, libraryIdsFuture, filter, config, monitoredAwait)
    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, userId.id, friendIdsFuture, libraryIdsFuture, filter, engine.recencyOnly, config, monitoredAwait)
    val articleScoreSource = new UriFromArticlesScoreVectorSource(articleSearcher, filter)

    if (debugFlags != 0) {
      engine.debug(this)
      libraryScoreSource.debug(this)
      keepScoreSource.debug(this)
      articleScoreSource.debug(this)
    }

    engine.explain(uriId.id, collector, libraryScoreSource, keepScoreSource, articleScoreSource)

    Explanation(query, labels, collector.rawScore, collector.getDetails(), collector.getBoostValues())
  }
}
