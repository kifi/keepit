package com.keepit.curator.commanders

import com.keepit.curator.model.{ SeedItem, ScoredSeedItem, UriScores }
import com.keepit.common.time._
import com.keepit.cortex.CortexServiceClient

import com.google.inject.{ Inject, Singleton }

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

import org.joda.time.Days

@Singleton
class UriScoringHelper @Inject() (cortex: CortexServiceClient) {

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
    val scoreTuples: Seq[Future[(Float, Float)]] = items.map { item =>
      cortex.userUriInterest(item.userId, item.uriId).map { score => //to be replaced with batch call when available
        val (overallOpt, recentOpt) = (score.global, score.recency)
        (overallOpt.map(uis => (0.5 * uis.score + 0.5) * uis.confidence).getOrElse(0.0).toFloat,
          recentOpt.map(uis => (0.5 * uis.score + 0.5) * uis.confidence).getOrElse(0.0).toFloat)
      }
    }
    Future.sequence(scoreTuples).map(_.unzip)
  }

  private def getRawSocialScores(items: Seq[SeedItem]): Future[Seq[Float]] = {
    Future.successful(items.map(_ => 0.0f)) //to be filled in by Tan
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
