package com.keepit.curator.commanders

import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.curator.model.{ SeedItemWithMultiplier, CuratorKeepInfoRepo, Keepers, SeedItem, ScoredSeedItem, UriScores }
import com.keepit.common.time._
import com.keepit.cortex.CortexServiceClient
import com.keepit.heimdal.HeimdalServiceClient
import com.keepit.model.{ NormalizedURI, HelpRankInfo }

import com.google.inject.{ Inject, Singleton }
import com.keepit.graph.GraphServiceClient
import com.keepit.model.User

import scala.concurrent.Future
import scala.collection.concurrent.TrieMap

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

import org.joda.time.Days

@Singleton
class UriScoringHelper @Inject() (
    graph: GraphServiceClient,
    keepInfoRepo: CuratorKeepInfoRepo,
    cortex: CortexServiceClient,
    heimdal: HeimdalServiceClient) extends Logging {

  private def getRawRecencyScores(items: Seq[SeedItemWithMultiplier]): Seq[Float] = items.map { item =>
    val daysOld = Days.daysBetween(item.lastSeen, currentDateTime).getDays()
    (1.0 / (Math.log(daysOld + 1.0) + 1)).toFloat
  }

  private def getRawPopularityScores(items: Seq[SeedItemWithMultiplier]): Seq[Float] = items.map { item =>
    val cappedPopularity = Math.min(item.timesKept, 100)
    (cappedPopularity / 100.0).toFloat
  }

  private def getRawPriorScores(items: Seq[SeedItemWithMultiplier]): Seq[Float] = items.map { item =>
    item.priorScore.getOrElse(0.0f)
  }

  private def getRawInterestScores(items: Seq[SeedItemWithMultiplier]): Future[(Seq[Float], Seq[Float])] = {
    val interestScores = cortex.batchUserURIsInterests(items.head.userId, items.map(_.uriId))
    interestScores.map { scores =>
      scores.map { score =>
        val (overallOpt, recentOpt) = (score.global, score.recency)
        (overallOpt.map(uis => if (uis.confidence > 0.5 && uis.score > 0) uis.score else 0.0).getOrElse(0.0).toFloat,
          recentOpt.map(uis => if (uis.confidence > 0.3 && uis.score > 0) uis.score else 0.0).getOrElse(0.0).toFloat)
      }.unzip
    }
  }

  private def getRawCurationScore(items: Seq[SeedItemWithMultiplier], boostedKeepers: Set[Id[User]]): Seq[Option[Float]] = {
    items.map(item =>
      item.keepers match {
        case Keepers.ReasonableNumber(users) if (!(boostedKeepers & users.toSet).isEmpty) => Some(0.75f)
        case _ => None
      })
  }

  // assume all items have same userId
  private def getRawSocialScores(items: Seq[SeedItemWithMultiplier]): Future[Seq[Float]] = {
    //convert user scores seq to map, assume there is no duplicate userId from graph service
    graph.getConnectedUserScores(items.head.userId, avoidFirstDegreeConnections = false).map { socialScores =>
      val socialScoreMap = socialScores.map { socialScore =>
        (socialScore.userId, socialScore.score.toFloat)
      }.toMap

      items.map(item =>
        item.keepers match {
          case Keepers.TooMany => 0.0f
          case Keepers.ReasonableNumber(users) => {
            var itemScore = 0.0f
            users.map(userId => itemScore += socialScoreMap.getOrElse(userId, 0.0f))
            Math.tanh(0.5 * itemScore).toFloat
          }
        })
    }.recover { //This needs to go once the graph is fixed
      case t: Throwable =>
        log.warn("Can't get social scores from graph.")
        Seq.fill[Float](items.size)(0.0f)
    }
  }

  private val uriHelpRankScores = TrieMap[Id[NormalizedURI], HelpRankInfo]() //This needs to go when we have proper caching on the help rank scores
  private def getRawHelpRankScores(items: Seq[SeedItemWithMultiplier]): Future[(Seq[Float], Seq[Float])] = {
    val helpRankInfos = heimdal.getHelpRankInfos(items.map(_.uriId).filterNot(uriHelpRankScores.contains))
    helpRankInfos.map { infos =>
      infos.foreach { info => uriHelpRankScores += (info.uriId -> info) }
      items.map { item =>
        val info = uriHelpRankScores(item.uriId)
        (Math.tanh(info.rekeepCount / 10).toFloat, Math.tanh(info.keepDiscoveryCount / 20).toFloat)
      }.unzip
    }

  }

  def apply(items: Seq[SeedItemWithMultiplier], boostedKeepers: Set[Id[User]]): Future[Seq[ScoredSeedItem]] = {
    require(items.map(_.userId).toSet.size <= 1, "Batch of seed items to score must be all for the same user")

    if (items.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val recencyScores = getRawRecencyScores(items)
      val popularityScores = getRawPopularityScores(items)
      val priorScores = getRawPriorScores(items)
      val curationScores = getRawCurationScore(items, boostedKeepers)

      val socialScoresFuture = getRawSocialScores(items)
      val interestScoresFuture = getRawInterestScores(items)
      val helpRankScoresFuture = getRawHelpRankScores(items)

      for (
        socialScores <- socialScoresFuture;
        (overallInterestScores, recentInterestScores) <- interestScoresFuture;
        (rekeepScores, discoveryScores) <- helpRankScoresFuture
      ) yield {
        for (i <- 0 until items.length) yield {
          val scores = UriScores(
            socialScore = socialScores(i),
            popularityScore = popularityScores(i),
            overallInterestScore = overallInterestScores(i),
            recentInterestScore = recentInterestScores(i),
            recencyScore = recencyScores(i),
            priorScore = priorScores(i),
            rekeepScore = rekeepScores(i),
            discoveryScore = discoveryScores(i),
            curationScore = curationScores(i),
            multiplier = Some(items(i).multiplier)
          )
          ScoredSeedItem(items(i).userId, items(i).uriId, scores)
        }
      }
    }
  }

}
