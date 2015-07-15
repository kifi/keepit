package com.keepit.search.engine.uri

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search._
import UriResultCollector._
import com.keepit.search.engine.result._
import com.keepit.search.engine._
import com.keepit.search.index.Searcher
import com.keepit.search.index.article.ArticleIndexable
import com.keepit.search.index.graph.keep.KeepIndexable
import com.keepit.search.tracking.{ MultiHashFilter, ClickedURI, ResultClickBoosts }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.math._

class UriSearchImpl(
    userId: Id[User],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig,
    engineBuilder: QueryEngineBuilder,
    articleSearcher: Searcher,
    keepSearcher: Searcher,
    librarySearcher: Searcher,
    friendIdsFuture: Future[Set[Long]],
    restrictedUserIdsFuture: Future[Set[Long]],
    libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    orgIdsFuture: Future[Set[Long]],
    clickBoostsFuture: Future[ResultClickBoosts],
    clickHistoryFuture: Future[MultiHashFilter[ClickedURI]],
    monitoredAwait: MonitoredAwait,
    timeLogs: SearchTimeLogs,
    lang: (Lang, Option[Lang])) extends UriSearch(articleSearcher, keepSearcher, timeLogs) with Logging {

  private[this] val isInitialSearch = filter.idFilter.isEmpty

  // get config params
  private[this] val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
  private[this] val dampingHalfDecayNetwork = config.asFloat("dampingHalfDecayNetwork")
  private[this] val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
  private[this] val minMyKeeps = config.asInt("minMyKeeps")
  private[this] val myKeepBoost = config.asFloat("myKeepBoost")
  private[this] val usefulPageBoost = config.asFloat("usefulPageBoost")
  private[this] val percentMatch = config.asFloat("percentMatch")
  private[this] val sharingBoostInNetwork = config.asFloat("sharingBoostInNetwork")

  private[this] val clickBoostsProvider: () => ResultClickBoosts = { () =>
    val ret = monitoredAwait.result(clickBoostsFuture, 5 seconds, s"getting clickBoosts")
    timeLogs.clickBoost()
    ret
  }

  private def executeTextSearch(engine: QueryEngine, explanation: Option[UriSearchExplanationBuilder] = None): (HitQueue, HitQueue, HitQueue) = {
    val maxTextHitsPerCategory = numHitsToReturn * 5
    val collector: UriResultCollector = if (engine.recencyOnly) {
      new UriResultCollectorWithNoBoost(maxTextHitsPerCategory, percentMatch / 100.0f, explanation)
    } else {
      new UriResultCollectorWithBoost(clickBoostsProvider, maxTextHitsPerCategory, percentMatch / 100.0f, sharingBoostInNetwork, explanation)
    }

    val libraryScoreSource = new UriFromLibraryScoreVectorSource(librarySearcher, keepSearcher, libraryIdsFuture, filter, config, monitoredAwait, explanation)
    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, userId.id, friendIdsFuture, restrictedUserIdsFuture, libraryIdsFuture, orgIdsFuture, filter, engine.recencyOnly, config, monitoredAwait, explanation)
    val articleScoreSource = new UriFromArticlesScoreVectorSource(articleSearcher, filter, explanation)

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

  def execute(): UriShardResult = {

    val engine = engineBuilder.build()
    debugLog(s"uri search engine created: ${engine.getQuery()}")

    val (myHits, networkHits, othersHits) = executeTextSearch(engine)

    val myTotal = myHits.totalHits
    val networkTotal = networkHits.totalHits

    val hits = createQueue(numHitsToReturn)

    // compute high score excluding others (an orphan uri sometimes makes results disappear)
    val highScore = {
      val highScore = max(myHits.highScore, networkHits.highScore)
      if (highScore > 0.0f) highScore else max(othersHits.highScore, highScore)
    }

    val usefulPages = if (clickHistoryFuture.isCompleted) Await.result(clickHistoryFuture, 0 millisecond) else MultiHashFilter.emptyFilter[ClickedURI]

    if (myHits.size > 0 && filter.includeMine) {
      myHits.toRankedIterator.foreach {
        case (hit, rank) =>
          hit.score = hit.score * myKeepBoost * (if (usefulPages.mayContain(hit.id, 2)) usefulPageBoost else 1.0f)
          hit.normalizedScore = (hit.score / highScore) * UriSearch.dampFunc(rank, dampingHalfDecayMine)
          hits.insert(hit)
      }
    }

    if (networkHits.size > 0 && filter.includeNetwork) {
      val queue = createQueue(numHitsToReturn - min(minMyKeeps, hits.size))
      hits.discharge(hits.size - minMyKeeps).foreach { h => queue.insert(h) }

      var rank = 0 // compute the rank on the fly (there may be unsafe hits from network)
      networkHits.toSortedList.foreach { hit =>
        if (((hit.visibility & Visibility.MEMBER) != 0) || ArticleIndexable.isSafe(articleSearcher, hit.id)) {
          hit.score = hit.score * (if ((hit.visibility & Visibility.MEMBER) != 0) myKeepBoost else 1.0f) * (if (usefulPages.mayContain(hit.id, 2)) usefulPageBoost else 1.0f)
          hit.normalizedScore = (hit.score / highScore) * UriSearch.dampFunc(rank, dampingHalfDecayNetwork)
          queue.insert(hit)
          rank += 1
        }
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
        if (KeepIndexable.isDiscoverable(keepSearcher, hit.id) && ArticleIndexable.isSafe(articleSearcher, hit.id)) {

          if (rank == 0) {
            // this is the first discoverable hit from others. compute the high score.
            othersHighScore = hit.score
            othersNorm = max(highScore, hit.score) * 1.1f // discount others hit
          }
          hit.score = hit.score * (if (usefulPages.mayContain(hit.id, 2)) usefulPageBoost else 1.0f)
          hit.normalizedScore = (hit.score / othersNorm) * UriSearch.dampFunc(rank, dampingHalfDecayOthers)
          hits.insert(hit)
          rank += 1
        } else {
          // todo(Léo): because of the following short-circuit and the overflowing othersHits queue, this isn't very meaningful and makes othersCounts non-deterministic in case of ties
          // todo(Léo): it may be possible to check the article discoverability in UriResultCollector or ArticleVisibility (performance?)
          othersTotal -= 1
        }
        hits.size < numHitsToReturn // until we fill up the queue
      }
    }

    val show = if (filter.isDefault && isInitialSearch && noFriendlyHits) false else (highScore > 0.6f || othersHighScore > 0.8f)

    timeLogs.processHits()
    timeLogs.done()
    timing()

    val uriShardResult = UriShardResult(hits.toSortedList.map(h => toKifiShardHit(h)), myTotal, networkTotal, othersTotal, show)

    debugLog(s"myHits: ${myHits.size()}/${myHits.totalHits}")
    debugLog(s"networkHits: ${networkHits.size()}/${networkHits.totalHits}")
    debugLog(s"othersHits: ${othersHits.size()}/${othersHits.totalHits}")
    debugLog(s"myTotal=$myTotal networkTotal=$networkTotal othersTotal=$othersTotal show=$show")
    debugLog(s"uriShardResult: ${uriShardResult.hits.map(_.id).mkString(",")}")

    uriShardResult
  }

  def explain(uriId: Id[NormalizedURI]): UriSearchExplanation = {
    val engine = engineBuilder.build()
    val labels = engineBuilder.getQueryLabels()
    val query = engine.getQuery()
    val explanation = new UriSearchExplanationBuilder(uriId, lang, query, labels)
    executeTextSearch(engine, Some(explanation))
    explanation.build()
  }
}
