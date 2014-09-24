package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search._
import com.keepit.search.engine.result.KifiResultCollector.HitQueue
import com.keepit.search.engine.result.{ KifiResultCollector, KifiNonUserResultCollector, KifiShardResult, KifiShardHit }
import org.apache.lucene.search._
import scala.concurrent.{ Future, Promise }

class KifiSearchNonUserImpl(
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig,
    engineBuilder: QueryEngineBuilder,
    articleSearcher: Searcher,
    keepSearcher: Searcher,
    friendIdsFuture: Future[Set[Long]],
    libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    monitoredAwait: MonitoredAwait,
    timeLogs: SearchTimeLogs) extends KifiSearch(articleSearcher, keepSearcher, timeLogs) with Logging {

  // get config params
  private[this] val percentMatch = config.asFloat("percentMatch")

  def executeTextSearch(maxTextHitsPerCategory: Int, promise: Option[Promise[_]] = None): HitQueue = {

    val engine = engineBuilder.build()
    debugLog("engine created")

    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, -1L, friendIdsFuture, libraryIdsFuture, filter, config, monitoredAwait)
    val numRecs1 = engine.execute(keepScoreSource)
    debugLog(s"UriFromKeepsScoreVectorSource executed recs=$numRecs1")

    val articleScoreSource = new UriFromArticlesScoreVectorSource(articleSearcher, filter)
    val numRec2 = engine.execute(articleScoreSource)
    debugLog(s"UriFromArticlesScoreVectorSource executed recs=${numRec2 - numRecs1}")

    timeLogs.search()

    if (debugFlags != 0) {
      if ((debugFlags & DebugOption.Trace.flag) != 0) engine.trace(debugTracedIds, this)
      if ((debugFlags & DebugOption.Library.flag) != 0) keepScoreSource.listLibraries(this)
    }

    val collector = new KifiNonUserResultCollector(maxTextHitsPerCategory, percentMatch / 100.0f)
    debugLog(s"KifiNonUserResultCollector created")
    engine.join(collector)
    debugLog(s"engine joined")

    collector.getResults()
  }

  def execute(): KifiShardResult = {
    val textHits = executeTextSearch(maxTextHitsPerCategory = numHitsToReturn)

    val total = textHits.totalHits
    val hits = KifiSearch.createQueue(numHitsToReturn)

    if (textHits.size > 0) {
      textHits.toRankedIterator.foreach {
        case (hit, rank) =>
          hit.normalizedScore = hit.score
          hits.insert(hit)
      }
    }

    timeLogs.processHits()
    timeLogs.done()
    timing()

    debugLog(s"total=${total}")

    KifiShardResult(hits.toSortedList.map(h => toKifiShardHit(h)), 0, 0, total, true)
  }

  override def toKifiShardHit(h: KifiResultCollector.Hit): KifiShardHit = {
    getKeepRecord(h.secondaryId) match {
      case Some(r) =>
        KifiShardHit(h.id, h.score, h.visibility, r.libraryId, h.secondaryId, r.title.getOrElse(""), r.url, r.externalId)
      case None =>
        val r = getArticleRecord(h.id).getOrElse(throw new Exception(s"missing article record: uri id = ${h.id}"))
        KifiShardHit(h.id, h.score, h.visibility, -1L, -1L, r.title, r.url, null)
    }
  }

  def explain(uriId: Id[NormalizedURI]): Option[(Query, Explanation)] = {
    throw new UnsupportedOperationException("explanation is not supported yet")
  }
}
