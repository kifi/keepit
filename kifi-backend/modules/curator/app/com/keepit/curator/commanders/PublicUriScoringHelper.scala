package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.logging.{ NamedStatsdTimer, Logging }
import com.keepit.common.time.currentDateTime
import com.keepit.cortex.CortexServiceClient
import com.keepit.curator.model.{
  Keepers,
  PublicSeedItemWithMultiplier,
  CuratorKeepInfoRepo,
  PublicUriScores,
  PublicScoredSeedItem
}
import com.keepit.graph.GraphServiceClient
import com.keepit.heimdal.HeimdalServiceClient
import com.keepit.model.{ User, HelpRankInfo, NormalizedURI }
import org.joda.time.Days
import com.keepit.common.time._

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class PublicUriScoringHelper @Inject() (
    graph: GraphServiceClient,
    keepInfoRepo: CuratorKeepInfoRepo,
    cortex: CortexServiceClient,
    heimdal: HeimdalServiceClient) extends Logging {

  private def getRawPopularityScores(items: Seq[PublicSeedItemWithMultiplier]): Seq[Float] = items.map { item =>
    val cappedPopularity = Math.min(item.timesKept, 100)
    cappedPopularity / 100f
  }

  private def getRawRecencyScores(items: Seq[PublicSeedItemWithMultiplier]): Seq[Float] = items.map { item =>
    val daysOld = Math.max(Days.daysBetween(item.lastSeen, currentDateTime).getDays, 0)
    (1.0 / (Math.log(daysOld + 1.0) + 1)).toFloat
  }

  private def getRawCurationScore(items: Seq[PublicSeedItemWithMultiplier], boostedKeepers: Set[Id[User]]): Seq[Option[Float]] = {
    items.map(item =>
      item.keepers match {
        case Keepers.ReasonableNumber(users) if (boostedKeepers & users.toSet).nonEmpty => Some(0.5f)
        case _ => None
      })
  }

  val uriHelpRankScores = TrieMap[Id[NormalizedURI], HelpRankInfo]() //This needs to go when we have proper caching on the help rank scores
  def getRawHelpRankScores(items: Seq[PublicSeedItemWithMultiplier]): Future[(Seq[Float], Seq[Float])] = {
    val timer = new NamedStatsdTimer("PublicUriScoringHelper.getRawHelpRankScores")
    val helpRankInfos = heimdal.getHelpRankInfos(items.map(_.uriId).filterNot(uriHelpRankScores.contains))
    helpRankInfos.map { infos =>
      timer.stopAndReport()
      infos.foreach { info => uriHelpRankScores += (info.uriId -> info) }
      items.map { item =>
        val info = uriHelpRankScores(item.uriId)
        (Math.tanh(info.rekeepCount / 10).toFloat, Math.tanh(info.keepDiscoveryCount / 20).toFloat)
      }.unzip
    }

  }

  def getPublicScoredUris(items: Seq[PublicSeedItemWithMultiplier], boostedKeepers: Set[Id[User]]): Future[Seq[PublicScoredSeedItem]] = {
    if (items.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val popularityScores = getRawPopularityScores(items)
      val helpRankScoresFuture = getRawHelpRankScores(items)
      val recencyScores = getRawRecencyScores(items)
      val curationScores = getRawCurationScore(items, boostedKeepers)
      for (
        (rekeepScores, discoveryScores) <- helpRankScoresFuture
      ) yield {
        for (i <- 0 until items.length) yield {
          val publicScore = PublicUriScores(
            popularityScore = popularityScores(i),
            recencyScore = recencyScores(i),
            rekeepScore = rekeepScores(i),
            discoveryScore = discoveryScores(i),
            curationScore = curationScores(i),
            multiplier = Some(items(i).multiplier)
          )
          PublicScoredSeedItem(items(i).uriId, publicScore)
        }
      }
    }
  }

  def apply(items: Seq[PublicSeedItemWithMultiplier], boostedKeepers: Set[Id[User]]): Future[Seq[PublicScoredSeedItem]] = {
    getPublicScoredUris(items, boostedKeepers)
  }
}
