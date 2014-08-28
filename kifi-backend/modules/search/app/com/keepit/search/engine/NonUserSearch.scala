package com.keepit.search.engine

import com.keepit.common.akka.{ SafeFuture, MonitoredAwait }
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search._
import com.keepit.search.engine.result.KifiResultCollector.HitQueue
import com.keepit.search.engine.result.{ KifiResultCollector, NonUserKifiResultCollector, KifiShardResult, KifiShardHit }
import org.apache.lucene.search._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{ Future, Promise }

class NonUserSearch(
    libId: Id[Library],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig,
    engineBuilder: QueryEngineBuilder,
    articleSearcher: Searcher,
    keepSearcher: Searcher,
    friendIdsFuture: Future[Set[Long]],
    libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long])],
    monitoredAwait: MonitoredAwait,
    timeLogs: SearchTimeLogs) extends KifiSearchUtil(articleSearcher, keepSearcher) with Logging {

  private[this] val currentTime = currentDateTime.getMillis()
  private[this] val idFilter = filter.idFilter

  // get config params
  private[this] val percentMatch = config.asFloat("percentMatch")

  def searchText(maxTextHitsPerCategory: Int, promise: Option[Promise[_]] = None): HitQueue = {

    val engine = engineBuilder.build()

    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, -1L, friendIdsFuture, libraryIdsFuture, filter, config, monitoredAwait)
    engine.execute(keepScoreSource)

    val articleScoreSource = new UriFromArticlesScoreVectorSource(articleSearcher, filter)
    engine.execute(articleScoreSource)

    val collector = new NonUserKifiResultCollector(maxTextHitsPerCategory, percentMatch)
    engine.join(collector)

    collector.getResults()
  }

  def search(): KifiShardResult = {
    val now = currentDateTime
    val textHits = searchText(maxTextHitsPerCategory = numHitsToReturn * 5)

    val tProcessHits = currentDateTime.getMillis()

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

    timeLogs.processHits = currentDateTime.getMillis() - tProcessHits
    timeLogs.total = currentDateTime.getMillis() - now.getMillis()
    timing()

    KifiShardResult(hits.toSortedList.map(h => toKifiShardHit(h)), total, 0, 0, true)
  }

  private[this] def toKifiShardHit(h: KifiResultCollector.Hit): KifiShardHit = {
    val recOpt = if (h.altId >= 0) getKeepRecord(h.altId) else getKeepRecord(libId.id, h.id)
    recOpt match {
      case Some(r) =>
        KifiShardHit(h.id, h.score, h.visibility, r.libraryId, r.title.getOrElse(""), r.url)
      case None =>
        val r = getArticleRecord(h.id).getOrElse(throw new Exception(s"missing article record: uri id = ${h.id}"))
        KifiShardHit(h.id, h.score, h.visibility, -1L, r.title, r.url)
    }
  }

  @inline private[this] def createQueue(sz: Int) = new HitQueue(sz)

  def explain(uriId: Id[NormalizedURI]): Option[(Query, Explanation)] = {
    throw new UnsupportedOperationException("explanation is not supported yet")
  }

  def timing(): Unit = {
    SafeFuture {
      timeLogs.send()
    }
  }
}
