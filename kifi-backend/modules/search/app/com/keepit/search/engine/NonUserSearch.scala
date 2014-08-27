package com.keepit.search.engine

import com.keepit.common.akka.{ SafeFuture, MonitoredAwait }
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search._
import com.keepit.search.engine.result.{ NonUserKifiResultCollector, KifiShardResult, KifiShardHit }
import com.keepit.search.engine.result.KifiResultCollector._
import org.apache.lucene.search.Query
import org.apache.lucene.search.Explanation
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.math._
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
    timeLogs: SearchTimeLogs) extends Logging {

  private[this] val currentTime = currentDateTime.getMillis()
  private[this] val idFilter = filter.idFilter
  private[this] val isInitialSearch = idFilter.isEmpty

  // get config params
  private[this] val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
  private[this] val percentMatch = config.asFloat("percentMatch")

  def searchText(maxTextHitsPerCategory: Int, promise: Option[Promise[_]] = None): HitQueue = {

    val engine = engineBuilder.build()

    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, -1L, friendIdsFuture, libraryIdsFuture, filter, config, monitoredAwait)
    engine.execute(keepScoreSource)

    val articleScoreSource = new UriFromArticlesScoreVectorSource(articleSearcher, filter)
    engine.execute(articleScoreSource)

    val collector = new NonUserKifiResultCollector(libId.id, maxTextHitsPerCategory, percentMatch)
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
          val score = hit.score * dampFunc(rank, dampingHalfDecayMine) // damping the scores by rank
          hit.score = score
          hit.normalizedScore = hit.score / highScore
          hits.insert(hit)
      }
    }

    timeLogs.processHits = currentDateTime.getMillis() - tProcessHits
    timeLogs.total = currentDateTime.getMillis() - now.getMillis()
    timing()

    KifiShardResult(hits.toSortedList.map(h => KifiShardHit(h.id, h.score, h.visibility, libId.id)), total, 0, true)
  }

  @inline private[this] def createQueue(sz: Int) = new HitQueue(sz)

  @inline private[this] def dampFunc(rank: Int, halfDecay: Double) = (1.0d / (1.0d + pow(rank.toDouble / halfDecay, 3.0d))).toFloat

  def explain(uriId: Id[NormalizedURI]): Option[(Query, Explanation)] = {
    throw new UnsupportedOperationException("explanation is not supported yet")
  }

  def timing(): Unit = {
    SafeFuture {
      timeLogs.send()
    }
  }
}
