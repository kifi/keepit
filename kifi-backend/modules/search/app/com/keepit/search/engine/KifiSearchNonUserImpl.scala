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
    libId: Id[Library],
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

    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, -1L, friendIdsFuture, libraryIdsFuture, filter, config, monitoredAwait)
    engine.execute(keepScoreSource)

    val articleScoreSource = new UriFromArticlesScoreVectorSource(articleSearcher, filter)
    engine.execute(articleScoreSource)

    val collector = new KifiNonUserResultCollector(maxTextHitsPerCategory, percentMatch)
    engine.join(collector)

    collector.getResults()
  }

  def execute(): KifiShardResult = {
    val textHits = executeTextSearch(maxTextHitsPerCategory = numHitsToReturn * 5)

    val total = textHits.totalHits

    val hits = createQueue(numHitsToReturn)

    // compute high score excluding others (an orphan uri sometimes makes results disappear)
    val highScore = textHits.highScore

    if (textHits.size > 0) {
      textHits.toRankedIterator.foreach {
        case (hit, rank) =>
          hit.normalizedScore = hit.score / highScore
          hits.insert(hit)
      }
    }

    timeLogs.processHits()
    timeLogs.done()
    timing()

    KifiShardResult(hits.toSortedList.map(h => toKifiShardHit(h)), total, 0, 0, true)
  }

  override def toKifiShardHit(h: KifiResultCollector.Hit): KifiShardHit = {
    getKeepRecord(h.keepId) match {
      case Some(r) =>
        KifiShardHit(h.id, h.score, h.visibility, r.libraryId, h.keepId, r.title.getOrElse(""), r.url, r.externalId)
      case None =>
        val r = getArticleRecord(h.id).getOrElse(throw new Exception(s"missing article record: uri id = ${h.id}"))
        KifiShardHit(h.id, h.score, h.visibility, -1L, -1L, r.title, r.url, null)
    }
  }

  def explain(uriId: Id[NormalizedURI]): Option[(Query, Explanation)] = {
    throw new UnsupportedOperationException("explanation is not supported yet")
  }
}
