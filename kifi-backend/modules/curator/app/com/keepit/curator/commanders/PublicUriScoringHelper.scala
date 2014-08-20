package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time.currentDateTime
import com.keepit.cortex.CortexServiceClient
import com.keepit.curator.model.{ CuratorKeepInfoRepo, PublicUriScores, PublicScoredSeedItem, SeedItemWithMultiplier, SeedItem, ScoredSeedItem }
import com.keepit.graph.GraphServiceClient
import com.keepit.heimdal.HeimdalServiceClient
import com.keepit.model.{ HelpRankInfo, NormalizedURI }
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

  private def getRawPopularityScores(items: Seq[SeedItemWithMultiplier]): Seq[Float] = items.map { item =>
    val cappedPopularity = Math.min(item.timesKept, 100)
    (cappedPopularity / 100.0).toFloat
  }

  private def getRawRecencyScores(items: Seq[SeedItemWithMultiplier]): Seq[Float] = items.map { item =>
    val daysOld = Days.daysBetween(item.lastSeen, currentDateTime).getDays()
    (1.0 / (Math.log(daysOld + 1.0) + 1)).toFloat
  }

  val uriHelpRankScores = TrieMap[Id[NormalizedURI], HelpRankInfo]() //This needs to go when we have proper caching on the help rank scores
  def getRawHelpRankScores(items: Seq[SeedItemWithMultiplier]): Future[(Seq[Float], Seq[Float])] = {
    val helpRankInfos = heimdal.getHelpRankInfos(items.map(_.uriId).filterNot(uriHelpRankScores.contains))
    helpRankInfos.map { infos =>
      infos.foreach { info => uriHelpRankScores += (info.uriId -> info) }
      items.map { item =>
        val info = uriHelpRankScores(item.uriId)
        (Math.tanh(info.rekeepCount / 10).toFloat, Math.tanh(info.keepDiscoveryCount / 20).toFloat)
      }.unzip
    }

  }

  def getPublicScoredUris(items: Seq[SeedItemWithMultiplier]): Future[Seq[PublicScoredSeedItem]] = {
    if (items.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val popularityScores = getRawPopularityScores(items)
      val helpRankScoresFuture = getRawHelpRankScores(items)
      val recencyScores = getRawRecencyScores(items)
      for (
        (rekeepScores, discoveryScores) <- helpRankScoresFuture
      ) yield {
        for (i <- 0 until items.length) yield {
          val publicScore = PublicUriScores(
            popularityScore = popularityScores(i),
            recencyScore = recencyScores(i),
            rekeepScore = rekeepScores(i),
            discoveryScore = discoveryScores(i),
            multiplier = Some(items(i).multiplier)
          )
          PublicScoredSeedItem(items(i).uriId, publicScore)
        }
      }
    }
  }

  def apply(items: Seq[SeedItemWithMultiplier]): Future[Seq[PublicScoredSeedItem]] = {
    getPublicScoredUris(items)
  }
}
