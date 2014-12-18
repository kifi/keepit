package com.keepit.search.engine.uri

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search._
import com.keepit.search.engine.result._
import com.keepit.search.engine.{ QueryEngine, QueryEngineBuilder, SearchTimeLogs }
import com.keepit.search.index.Searcher

import scala.concurrent.Future

class UriSearchNonUserImpl(
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
    timeLogs: SearchTimeLogs) extends UriSearch(articleSearcher, keepSearcher, timeLogs) with Logging {

  // get config params
  private[this] val percentMatch = config.asFloat("percentMatch")

  private def executeTextSearch(engine: QueryEngine, explanation: Option[UriSearchExplanationBuilder] = None): HitQueue = {

    val collector = new NonUserUriResultCollector(numHitsToReturn, percentMatch / 100.0f, explanation)

    val libraryScoreSource = new UriFromLibraryScoreVectorSource(librarySearcher, keepSearcher, libraryIdsFuture, filter, config, monitoredAwait)
    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, -1L, friendIdsFuture, libraryIdsFuture, filter, engine.recencyOnly, config, monitoredAwait)
    val articleScoreSource = new UriFromArticlesScoreVectorSource(articleSearcher, filter)

    if (debugFlags != 0) {
      engine.debug(this)
      keepScoreSource.debug(this)
    }

    engine.execute(collector, libraryScoreSource, keepScoreSource, articleScoreSource)

    timeLogs.search()

    collector.getResults()
  }

  def execute(): UriShardResult = {

    val engine = engineBuilder.build()
    debugLog("engine created")

    val textHits = executeTextSearch(engine)

    val total = textHits.totalHits
    val hits = UriSearch.createQueue(numHitsToReturn)

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

    UriShardResult(hits.toSortedList.map(h => toKifiShardHit(h)), 0, 0, total, true)
  }

  override def toKifiShardHit(h: Hit): UriShardHit = {
    getKeepRecord(h.secondaryId) match {
      case Some(r) =>
        UriShardHit(h.id, h.score, h.visibility, r.libraryId, h.secondaryId, r.title.getOrElse(""), r.url, r.externalId)
      case None =>
        val r = getArticleRecord(h.id).getOrElse(throw new Exception(s"missing article record: uri id = ${h.id}"))
        UriShardHit(h.id, h.score, h.visibility, -1L, -1L, r.title, r.url, null)
    }
  }

  def explain(uriId: Id[NormalizedURI]): UriSearchExplanation = {
    val engine = engineBuilder.build()
    val labels = engineBuilder.getQueryLabels()
    val query = engine.getQuery()
    val explanation = new UriSearchExplanationBuilder(uriId, query, labels)
    executeTextSearch(engine, Some(explanation))
    explanation.build()
  }
}
