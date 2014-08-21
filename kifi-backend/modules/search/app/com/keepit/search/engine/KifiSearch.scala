package com.keepit.search.engine

import com.keepit.common.akka.{ SafeFuture, MonitoredAwait }
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search._
import com.keepit.search.engine.result.{ KifiShardResult, KifiShardHit, KifiResultCollector }
import com.keepit.search.engine.result.KifiResultCollector._
import com.keepit.search.graph.keep.KeepFields
import org.apache.lucene.index.Term
import org.apache.lucene.search.Query
import org.apache.lucene.search.Explanation
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.math._
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration._
import com.keepit.search.tracker.ClickedURI
import com.keepit.search.tracker.ResultClickBoosts

class KifiSearch(
    userId: Id[User],
    lang1: Lang,
    lang2: Option[Lang],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig,
    engine: QueryEngine,
    articleSearcher: Searcher,
    keepSearcher: Searcher,
    libraryIdsFuture: Future[(Seq[Long], Seq[Long], Seq[Long])],
    clickBoostsFuture: Future[ResultClickBoosts],
    clickHistoryFuture: Future[MultiHashFilter[ClickedURI]],
    monitoredAwait: MonitoredAwait,
    timeLogs: SearchTimeLogs) extends Logging {

  private[this] val currentTime = currentDateTime.getMillis()
  private[this] val idFilter = filter.idFilter
  private[this] val isInitialSearch = idFilter.isEmpty

  // get config params
  private[this] val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
  private[this] val dampingHalfDecayFriends = config.asFloat("dampingHalfDecayFriends")
  private[this] val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
  private[this] val similarity = Similarity(config.asString("similarity"))
  private[this] val minMyBookmarks = config.asInt("minMyBookmarks")
  private[this] val myBookmarkBoost = config.asFloat("myBookmarkBoost")
  private[this] val usefulPageBoost = config.asFloat("usefulPageBoost")
  private[this] val forbidEmptyFriendlyHits = config.asBoolean("forbidEmptyFriendlyHits")
  private[this] val percentMatch = config.asFloat("percentMatch")

  def searchText(maxTextHitsPerCategory: Int, promise: Option[Promise[_]] = None): (HitQueue, HitQueue, HitQueue) = {

    keepSearcher.setSimilarity(similarity)
    val keepScoreSource = new UriFromKeepsScoreVectorSource(keepSearcher, libraryIdsFuture, filter.idFilter, monitoredAwait)
    engine.execute(keepScoreSource)

    articleSearcher.setSimilarity(similarity)
    val articleScoreSource = new UriFromArticlesScoreVectorSource(articleSearcher, filter.idFilter)
    engine.execute(articleScoreSource)

    val tClickBoosts = currentDateTime.getMillis()
    val clickBoosts = monitoredAwait.result(clickBoostsFuture, 5 seconds, s"getting clickBoosts for user Id $userId")
    timeLogs.getClickBoost = currentDateTime.getMillis() - tClickBoosts

    val collector = new KifiResultCollector(clickBoosts, maxTextHitsPerCategory, percentMatch)
    engine.join(collector)

    collector.getResults()
  }

  def search(): KifiShardResult = {
    val now = currentDateTime
    val (myHits, friendsHits, othersHits) = searchText(maxTextHitsPerCategory = numHitsToReturn * 5)

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
          val score = hit.score * dampFunc(rank, dampingHalfDecayMine) // damping the scores by rank
          hit.score = score * myBookmarkBoost * (if (usefulPages.mayContain(hit.id, 2)) usefulPageBoost else 1.0f)
          hit.normalizedScore = hit.score / highScore
          hits.insert(hit)
      }
    }

    if (friendsHits.size > 0 && filter.includeFriends) {
      val queue = createQueue(numHitsToReturn - min(minMyBookmarks, hits.size))
      hits.discharge(hits.size - minMyBookmarks).foreach { h => queue.insert(h) }

      friendsHits.toRankedIterator.foreach {
        case (hit, rank) =>
          val score = hit.score * dampFunc(rank, dampingHalfDecayFriends) // damping the scores by rank
          hit.score = score * (if (usefulPages.mayContain(hit.id, 2)) usefulPageBoost else 1.0f)
          hit.normalizedScore = hit.score / highScore
          queue.insert(hit)
      }
      queue.foreach { h => hits.insert(h) }
    }

    val noFriendlyHits = (hits.size == 0)

    var othersHighScore = -1.0f
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
          val score = hit.score * dampFunc(rank, dampingHalfDecayOthers) // damping the scores by rank
          hit.score = score * (if (usefulPages.mayContain(hit.id, 2)) usefulPageBoost else 1.0f)
          hit.normalizedScore = hit.score / othersNorm
          queue.insert(hit)
          rank += 1
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

    KifiShardResult(hits.toSortedList.map(h => KifiShardHit(h.id, h.score, h.visibility, -1L)), myTotal, friendsTotal, show) // TODO: library id
  }

  @inline private[this] def isDiscoverable(id: Long) = keepSearcher.has(new Term(KeepFields.discoverableUriField, id.toString))

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
