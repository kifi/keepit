package com.keepit.search.engine.library

import com.keepit.common.db.Id
import com.keepit.model.{ Library, Keep, User }
import com.keepit.search.engine._
import com.keepit.search.engine.uri.UriSearch
import com.keepit.search.index.Searcher
import com.keepit.search.{ SearchContext, Lang, SearchConfig }
import com.keepit.search.engine.result.{ HitQueue }
import com.keepit.common.logging.Logging
import scala.concurrent.Future
import com.keepit.common.akka.{ SafeFuture, MonitoredAwait }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.math._
import com.keepit.search.index.graph.keep.KeepRecord

class LibrarySearch(
    userId: Id[User],
    numHitsToReturn: Int,
    context: SearchContext,
    config: SearchConfig,
    engineBuilder: QueryEngineBuilder,
    librarySearcher: Searcher,
    libraryMembershipSearcher: Searcher,
    keepSearcher: Searcher,
    userSearcher: Searcher,
    libraryQualityEvaluator: LibraryQualityEvaluator,
    friendIdsFuture: Future[Set[Long]],
    restrictedUserIdsFuture: Future[Set[Long]],
    libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    orgIdsFuture: Future[Set[Long]],
    monitoredAwait: MonitoredAwait,
    timeLogs: SearchTimeLogs,
    explain: Option[(Id[Library], Lang, Option[Lang])]) extends DebugOption with Logging {
  private[this] val percentMatch = config.asFloat("percentMatch")
  private[this] val myLibraryBoost = config.asFloat("myLibraryBoost")

  def execute(): LibraryShardResult = {

    val ((myHits, networkHits, othersHits), explanation) = executeTextSearch()
    debugLog(s"myHits: ${myHits.size()}/${myHits.totalHits}")
    debugLog(s"networkHits: ${networkHits.size()}/${networkHits.totalHits}")
    debugLog(s"othersHits: ${othersHits.size()}/${othersHits.totalHits}")

    val libraryShardResult = LibrarySearch.merge(myHits, networkHits, othersHits, numHitsToReturn, context, config, explanation)(keepId => KeepRecord.retrieve(keepSearcher, keepId).get)
    debugLog(s"libraryShardResult: ${libraryShardResult.hits.map(_.id).mkString(",")}")
    timeLogs.processHits()
    timeLogs.done()

    SafeFuture { timeLogs.send() }
    debugLog(timeLogs.toString)

    libraryShardResult
  }

  private def executeTextSearch(): ((HitQueue, HitQueue, HitQueue), Option[LibrarySearchExplanation]) = {

    val engine = engineBuilder.build()
    debugLog(s"library search engine created: ${engine.getQuery()}")
    val explanation = explain.map {
      case (libraryId, firstLang, secondLang) =>
        val labels = engineBuilder.getQueryLabels()
        val query = engine.getQuery()
        new LibrarySearchExplanationBuilder(libraryId, (firstLang, secondLang), query, labels)
    }

    val collector = new LibraryResultCollector(librarySearcher, libraryMembershipSearcher, keepSearcher, numHitsToReturn * 5, myLibraryBoost, percentMatch / 100.0f, libraryQualityEvaluator, explanation)

    val userScoreSource = new LibraryFromUserScoreVectorSource(librarySearcher, userSearcher, userId.id, friendIdsFuture, restrictedUserIdsFuture, libraryIdsFuture, orgIdsFuture, context, config, monitoredAwait, explanation)
    val keepScoreSource = new LibraryFromKeepsScoreVectorSource(keepSearcher, userId.id, friendIdsFuture, restrictedUserIdsFuture, libraryIdsFuture, orgIdsFuture, context, config, monitoredAwait, librarySearcher, libraryQualityEvaluator, explanation)
    val libraryScoreSource = new LibraryScoreVectorSource(librarySearcher, userId.id, friendIdsFuture, restrictedUserIdsFuture, libraryIdsFuture, orgIdsFuture, context, config, monitoredAwait, explanation)

    if (debugFlags != 0) {
      engine.debug(this)
      userScoreSource.debug(this)
      keepScoreSource.debug(this)
      libraryScoreSource.debug(this)
    }

    engine.execute(collector, userScoreSource, keepScoreSource, libraryScoreSource)

    timeLogs.search()

    (collector.getResults(), explanation.map(_.build()))
  }

}

object LibrarySearch extends Logging {
  def merge(myHits: HitQueue, networkHits: HitQueue, othersHits: HitQueue, maxHits: Int, context: SearchContext, config: SearchConfig, explanation: Option[LibrarySearchExplanation])(keepsRecords: Id[Keep] => KeepRecord): LibraryShardResult = {

    val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
    val dampingHalfDecayNetwork = config.asFloat("dampingHalfDecayNetwork")
    val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")
    val minMyLibraries = config.asInt("minMyLibraries")

    val isInitialSearch = context.idFilter.isEmpty

    val hits = UriSearch.createQueue(maxHits)

    // compute high score excluding others (an orphan uri sometimes makes results disappear)
    val highScore = {
      val highScore = max(myHits.highScore, networkHits.highScore)
      if (highScore > 0.0f) highScore else max(othersHits.highScore, highScore)
    }

    if (myHits.size > 0 && context.filter.includeMine) {
      myHits.toRankedIterator.foreach {
        case (hit, rank) =>
          hit.normalizedScore = (hit.score / highScore) * UriSearch.dampFunc(rank, dampingHalfDecayMine)
          hits.insert(hit)
      }
    }

    if (networkHits.size > 0 && context.filter.includeNetwork) {
      val queue = UriSearch.createQueue(maxHits - min(minMyLibraries, hits.size))
      hits.discharge(hits.size - minMyLibraries).foreach { h => queue.insert(h) }

      networkHits.toRankedIterator.foreach {
        case (hit, rank) =>
          hit.normalizedScore = (hit.score / highScore) * UriSearch.dampFunc(rank, dampingHalfDecayNetwork)
          queue.insert(hit)
      }
      queue.foreach { h => hits.insert(h) }
    }

    val noFriendlyHits = (hits.size == 0)

    val othersHighScore = othersHits.highScore
    if (hits.size < maxHits && othersHits.size > 0 && context.filter.includeOthers) {
      othersHits.toRankedIterator.take(maxHits - hits.size).foreach {
        case (hit, rank) =>
          val othersNorm = max(highScore, hit.score) * 1.1f // discount others hit
          hit.normalizedScore = (hit.score / othersNorm) * UriSearch.dampFunc(rank, dampingHalfDecayOthers)
          hits.insert(hit)
      }
    }

    val show = if (context.isDefault && isInitialSearch && noFriendlyHits) false else (highScore > 0.6f || othersHighScore > 0.8f)

    val libraryShardHits = hits.toSortedList.map { h =>
      val keep = if (h.secondaryId > 0) {
        val keepId = Id[Keep](h.secondaryId)
        val keepRecord = keepsRecords(keepId)
        Some((keepId, keepRecord))
      } else None
      LibraryShardHit(Id(h.id), h.score, h.visibility, keep)
    }

    LibraryShardResult(libraryShardHits, show, explanation)
  }

  def partition(libraryShardHits: Seq[LibraryShardHit]): (HitQueue, HitQueue, HitQueue, Map[Id[Keep], KeepRecord]) = {
    val maxHitsPerCategory = libraryShardHits.length
    val myHits = UriSearch.createQueue(maxHitsPerCategory)
    val networkHits = UriSearch.createQueue(maxHitsPerCategory)
    val othersHits = UriSearch.createQueue(maxHitsPerCategory)

    val keepRecords = libraryShardHits.map { hit =>
      val visibility = hit.visibility
      val relevantQueue = if ((visibility & Visibility.OWNER) != 0) {
        myHits
      } else if ((visibility & (Visibility.MEMBER | Visibility.NETWORK)) != 0) {
        networkHits
      } else {
        othersHits
      }
      relevantQueue.insert(hit.id.id, hit.score, visibility, hit.keep.map(_._1.id).getOrElse(-1))
      hit.keep
    }.flatten.toMap
    (myHits, networkHits, othersHits, keepRecords)
  }
}
