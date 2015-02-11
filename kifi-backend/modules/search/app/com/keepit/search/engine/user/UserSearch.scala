package com.keepit.search.engine.user

import com.keepit.common.db.Id
import com.keepit.model.{ Library, Keep, User }
import com.keepit.search.engine._
import com.keepit.search.engine.uri.UriSearch
import com.keepit.search.index.Searcher
import com.keepit.search.{ Lang, SearchConfig, SearchFilter }
import com.keepit.search.engine.result.{ ResultCollector, HitQueue }
import com.keepit.common.logging.Logging
import scala.concurrent.Future
import com.keepit.common.akka.{ SafeFuture, MonitoredAwait }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.math._
import com.keepit.search.index.graph.keep.KeepRecord

class UserSearch(
    userId: Id[User],
    numHitsToReturn: Int,
    filter: SearchFilter,
    config: SearchConfig,
    engineBuilder: QueryEngineBuilder,
    librarySearcher: Searcher,
    keepSearcher: Searcher,
    userSearcher: Searcher,
    libraryQualityEvaluator: LibraryQualityEvaluator,
    friendIdsFuture: Future[Set[Long]],
    libraryIdsFuture: Future[(Set[Long], Set[Long], Set[Long], Set[Long])],
    monitoredAwait: MonitoredAwait,
    timeLogs: SearchTimeLogs,
    explain: Option[(Id[User], Lang, Option[Lang])]) extends DebugOption with Logging {
  private[this] val percentMatch = config.asFloat("percentMatch")
  private[this] val myFriendBoost = config.asFloat("myFriendBoost")

  def execute(): UserShardResult = {

    val ((myHits, othersHits), explanation) = executeTextSearch()
    debugLog(s"myHits: ${myHits.totalHits}")
    debugLog(s"othersHits: ${othersHits.totalHits}")

    val userShardResult = UserSearch.merge(myHits, othersHits, numHitsToReturn, filter, config, explanation)
    debugLog(s"userShardResult: ${userShardResult.hits.map(_.id).mkString(",")}")
    timeLogs.processHits()
    timeLogs.done()

    SafeFuture { timeLogs.send() }
    debugLog(timeLogs.toString)

    userShardResult
  }

  private def executeTextSearch(): ((HitQueue, HitQueue), Option[UserSearchExplanation]) = {

    val engine = engineBuilder.build()
    debugLog("user search engine created")

    val explanation = explain.map {
      case (libraryId, firstLang, secondLang) =>
        val labels = engineBuilder.getQueryLabels()
        val query = engine.getQuery()
        new UserSearchExplanationBuilder(libraryId, (firstLang, secondLang), query, labels)
    }

    val collector = new UserResultCollector(librarySearcher, keepSearcher, numHitsToReturn * 5, myFriendBoost, percentMatch / 100.0f, libraryQualityEvaluator, explanation)

    val userScoreSource = new UserScoreVectorSource(userSearcher, userId.id, friendIdsFuture, libraryIdsFuture, filter, config, monitoredAwait, explanation)
    val keepScoreSource = new UserFromKeepsScoreVectorSource(keepSearcher, userId.id, friendIdsFuture, libraryIdsFuture, filter, config, monitoredAwait, libraryQualityEvaluator, explanation)
    val libraryScoreSource = new UserFromLibrariesScoreVectorSource(librarySearcher, userId.id, friendIdsFuture, libraryIdsFuture, filter, config, monitoredAwait, explanation)

    if (debugFlags != 0) {
      engine.debug(this)
      keepScoreSource.debug(this)
    }

    engine.execute(collector, userScoreSource, keepScoreSource, libraryScoreSource)

    timeLogs.search()

    (collector.getResults(), explanation.map(_.build()))
  }

}

object UserSearch extends Logging {
  def merge(myHits: HitQueue, othersHits: HitQueue, maxHits: Int, filter: SearchFilter, config: SearchConfig, explanation: Option[UserSearchExplanation]): UserShardResult = {

    val dampingHalfDecayMine = config.asFloat("dampingHalfDecayMine")
    val dampingHalfDecayOthers = config.asFloat("dampingHalfDecayOthers")

    val hits = UriSearch.createQueue(maxHits)

    // compute high score excluding others (an orphan uri sometimes makes results disappear)
    val highScore = {
      val highScore = myHits.highScore
      if (highScore > 0.0f) highScore else max(othersHits.highScore, highScore)
    }

    if (myHits.size > 0 && (filter.includeMine || filter.includeFriends)) {
      myHits.toRankedIterator.foreach {
        case (hit, rank) =>
          hit.normalizedScore = (hit.score / highScore) * UriSearch.dampFunc(rank, dampingHalfDecayMine)
          hits.insert(hit)
      }
    }

    if (hits.size < maxHits && othersHits.size > 0 && filter.includeOthers) {
      othersHits.toRankedIterator.take(maxHits - hits.size).foreach {
        case (hit, rank) =>
          val othersNorm = max(highScore, hit.score) * 1.1f // discount others hit
          hit.normalizedScore = (hit.score / othersNorm) * UriSearch.dampFunc(rank, dampingHalfDecayOthers)
          hits.insert(hit)
      }
    }

    val userShardHits = hits.toSortedList.map { h =>
      val library = if (h.secondaryId > 0) {
        val libraryId = Id[Library](h.secondaryId)
        Some(libraryId)
      } else None
      UserShardHit(Id(h.id), h.score, h.visibility, library)
    }

    UserShardResult(userShardHits, explanation)
  }

  def partition(userShardHits: Seq[UserShardHit]): (HitQueue, HitQueue) = {
    val maxHitsPerCategory = userShardHits.length
    val myHits = UriSearch.createQueue(maxHitsPerCategory)
    val othersHits = UriSearch.createQueue(maxHitsPerCategory)

    userShardHits.foreach { hit =>
      val visibility = hit.visibility
      val relevantQueue = if ((visibility & (Visibility.OWNER | Visibility.MEMBER | Visibility.NETWORK)) != 0) {
        myHits
      } else {
        othersHits
      }
      relevantQueue.insert(hit.id.id, hit.score, visibility, hit.library.map(_.id).getOrElse(-1))
    }
    (myHits, othersHits)
  }
}
