package com.keepit.search.engine

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search._
import com.keepit.search.engine.explain.{ ScoreDetailCollector, Explanation }
import com.keepit.search.engine.result._
import scala.concurrent.{ Future, Promise }

class KifiSearchNonUserImpl(
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig,
    engineBuilder: QueryEngineBuilder,
    articleSearcher: Searcher,
    keepSearcher: Searcher,
    librarySearcher: Searcher,
    friendIdsFuture: Future[Set[Long]],
    libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    monitoredAwait: MonitoredAwait,
    timeLogs: SearchTimeLogs) extends KifiSearch(articleSearcher, keepSearcher, timeLogs) with Logging {

  // get config params
  private[this] val percentMatch = config.asFloat("percentMatch")

  def executeTextSearch(maxTextHitsPerCategory: Int, promise: Option[Promise[_]] = None): HitQueue = {

    val engine = engineBuilder.build()
    debugLog("engine created")

    val collector = new KifiNonUserResultCollector(maxTextHitsPerCategory, percentMatch / 100.0f)
    val libraryScoreSource = new UriFromLibraryScoreVectorSource(librarySearcher, keepSearcher, libraryIdsFuture, filter, config, monitoredAwait)
    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, -1L, friendIdsFuture, libraryIdsFuture, filter, false, config, monitoredAwait)
    val articleScoreSource = new UriFromArticlesScoreVectorSource(articleSearcher, filter)

    if (debugFlags != 0) {
      engine.debug(this)
      keepScoreSource.debug(this)
    }

    engine.execute(collector, libraryScoreSource, keepScoreSource, articleScoreSource)

    timeLogs.search()

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

  override def toKifiShardHit(h: Hit): KifiShardHit = {
    getKeepRecord(h.secondaryId) match {
      case Some(r) =>
        KifiShardHit(h.id, h.score, h.visibility, r.libraryId, h.secondaryId, r.title.getOrElse(""), r.url, r.externalId)
      case None =>
        val r = getArticleRecord(h.id).getOrElse(throw new Exception(s"missing article record: uri id = ${h.id}"))
        KifiShardHit(h.id, h.score, h.visibility, -1L, -1L, r.title, r.url, null)
    }
  }

  def explain(uriId: Id[NormalizedURI]): Explanation = {
    val engine = engineBuilder.build()
    val labels = engineBuilder.getQueryLabels()
    val query = engine.getQuery()
    val collector = new ScoreDetailCollector(uriId.id, None, percentMatch / 100.0f, None)

    val libraryScoreSource = new UriFromLibraryScoreVectorSource(librarySearcher, keepSearcher, libraryIdsFuture, filter, config, monitoredAwait)
    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, -1L, friendIdsFuture, libraryIdsFuture, filter, engine.recencyOnly, config, monitoredAwait)
    val articleScoreSource = new UriFromArticlesScoreVectorSource(articleSearcher, filter)

    if (debugFlags != 0) {
      engine.debug(this)
      libraryScoreSource.debug(this)
      keepScoreSource.debug(this)
      articleScoreSource.debug(this)
    }

    engine.explain(uriId.id, collector, libraryScoreSource, keepScoreSource, articleScoreSource)

    Explanation(query, labels, collector.getMatchingValues(), collector.getBoostValues(), collector.rawScore, collector.boostedScore, collector.scoreComputation, collector.getDetails())
  }
}
