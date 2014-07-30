package com.keepit.curator.commanders

import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.curator.model.{ CuratorKeepInfoRepo, Keepers, SeedItem, ScoredSeedItem, UriScores }

import com.keepit.common.time._
import com.keepit.cortex.CortexServiceClient

import com.google.inject.{ Inject, Singleton }
import com.keepit.graph.GraphServiceClient
import com.keepit.model.User

import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

import org.joda.time.Days

@Singleton
class UriScoringHelper @Inject() (
    graph: GraphServiceClient,
    keepInfoRepo: CuratorKeepInfoRepo,
    cortex: CortexServiceClient) extends Logging {

  private def getRawRecencyScores(items: Seq[SeedItem]): Seq[Float] = items.map { item =>
    val daysOld = Days.daysBetween(item.lastSeen, currentDateTime).getDays()
    (1.0 / (Math.log(daysOld + 1.0) + 1)).toFloat
  }

  private def getRawPopularityScores(items: Seq[SeedItem]): Seq[Float] = items.map { item =>
    val cappedPopularity = Math.min(item.timesKept, 100)
    (cappedPopularity / 100.0).toFloat
  }

  private def getRawPriorScores(items: Seq[SeedItem]): Seq[Float] = items.map { item =>
    item.priorScore.getOrElse(0.0f)
  }

  private def getRawInterestScores(items: Seq[SeedItem]): Future[(Seq[Float], Seq[Float])] = {
    val interestScores = cortex.batchUserURIsInterests(items.head.userId, items.map(_.uriId))
    interestScores.map { scores =>
      scores.map { score =>
        val (overallOpt, recentOpt) = (score.global, score.recency)
        (overallOpt.map(uis => (0.5 * uis.score + 0.5) * uis.confidence).getOrElse(0.0).toFloat,
          recentOpt.map(uis => (0.5 * uis.score + 0.5) * uis.confidence).getOrElse(0.0).toFloat)
      }.unzip
    }
  }

  // assume all items have same userId
  def getRawSocialScores(items: Seq[SeedItem]): Future[Seq[Float]] = {
    if (items.isEmpty) {
      Future.successful(Seq.empty)
    } else {

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
              itemScore
            }
          })
      }.recover { //This needs to go once the graph is fixed
        case t: Throwable =>
          log.warn("Can't get social scores from graph.")
          Seq.fill[Float](items.size)(0.0f)
      }
    }
  }

  def apply(items: Seq[SeedItem]): Future[Seq[ScoredSeedItem]] = {
    require(items.map(_.userId).toSet.size == 1, "Batch of seed items to score must be non empty and all for the same user")

    val recencyScores = getRawRecencyScores(items)
    val popularityScores = getRawPopularityScores(items)
    val priorScores = getRawPriorScores(items)

    val socialScoresFuture = getRawSocialScores(items)
    val interestScoresFuture = getRawInterestScores(items)

    for (
      socialScores <- socialScoresFuture;
      (overallInterestScores, recentInterestScores) <- interestScoresFuture
    ) yield {
      for (i <- 0 until items.length) yield {
        val scores = UriScores(
          socialScore = socialScores(i),
          popularityScore = popularityScores(i),
          overallInterestScore = overallInterestScores(i),
          recentInterestScore = recentInterestScores(i),
          recencyScore = recencyScores(i),
          priorScore = priorScores(i)
        )
        ScoredSeedItem(items(i).userId, items(i).uriId, scores)
      }
    }

  }

}
