package com.keepit.curator.commanders

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.cortex.core.CortexVersionCommander
import com.keepit.curator.model.{ SeedItemWithMultiplier, CuratorKeepInfoRepo, Keepers, ScoredSeedItem, UriScores }
import com.keepit.common.time._
import com.keepit.cortex.CortexServiceClient
import com.keepit.heimdal.HeimdalServiceClient

import com.google.inject.{ Inject, Singleton }
import com.keepit.graph.GraphServiceClient
import com.keepit.model.User

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class UriScoringHelper @Inject() (
    graph: GraphServiceClient,
    keepInfoRepo: CuratorKeepInfoRepo,
    cortex: CortexServiceClient,
    cortexVersion: CortexVersionCommander,
    heimdal: HeimdalServiceClient,
    publicScoring: PublicUriScoringHelper) extends Logging {

  private def getRawPriorScores(items: Seq[SeedItemWithMultiplier]): Seq[Float] = items.map { item =>
    item.priorScore.getOrElse(0.0f)
  }

  private def getRawInterestScores(items: Seq[SeedItemWithMultiplier]): Future[(Seq[Float], Seq[Float], Seq[Float])] = {
    val userIdOpt = items.headOption.map(_.userId)

    userIdOpt match {
      case None => Future.successful((Seq(), Seq(), Seq()))
      case Some(userId) =>
        cortexVersion.getExperimentalLDAVersionForUser(userId).flatMap { implicit versionOpt =>
          val interestScores = cortex.batchUserURIsInterests(items.head.userId, items.map(_.uriId))
          interestScores.map { scores =>
            scores.map { score =>
              val (overallOpt, recentOpt, libOpt) = (score.global, score.recency, score.libraryInduced)
              (overallOpt.map(uis => if (uis.confidence > 0.3 && uis.score > 0) uis.score else 0f).getOrElse(0f),
                recentOpt.map(uis => if (uis.confidence > 0.2 && uis.score > 0) uis.score else 0f).getOrElse(0f),
                libOpt.map { uis => if (uis.confidence > 0.2 && uis.score > 0) uis.score else 0f }.getOrElse(0f))
            }.unzip3
          }
        }
    }
  }

  // assume all items have same userId
  private def getRawSocialScores(items: Seq[SeedItemWithMultiplier], boostedKeepers: Set[Id[User]]): Future[Seq[Float]] = {
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
            users.foreach(userId => itemScore += socialScoreMap.getOrElse(userId, 0.0f))
            Math.tanh(0.5 * itemScore).toFloat
          }
        })
    }.recover { //This needs to go once the graph is fixed
      case t: Throwable =>
        log.warn("Can't get social scores from graph.")
        Seq.fill[Float](items.size)(0.0f)
    }
  }

  def apply(items: Seq[SeedItemWithMultiplier], boostedKeepers: Set[Id[User]]): Future[Seq[ScoredSeedItem]] = {
    require(items.map(_.userId).toSet.size <= 1, "Batch of seed items to score must be all for the same user")
    if (items.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val publicScoresFut = publicScoring(items.map(item => item.makePublicSeedItemWithMultiplier), boostedKeepers)
      val priorScores = getRawPriorScores(items)
      val socialScoresFuture = getRawSocialScores(items, boostedKeepers)
      val interestScoresFuture = getRawInterestScores(items)
      for {
        socialScores <- socialScoresFuture
        (overallInterestScores, recentInterestScores, libScores) <- interestScoresFuture
        publicScores <- publicScoresFut
      } yield {
        for (i <- 0 until items.length) yield {
          val scores = UriScores(
            socialScore = socialScores(i),
            popularityScore = publicScores(i).publicUriScores.popularityScore,
            overallInterestScore = overallInterestScores(i),
            recentInterestScore = recentInterestScores(i),
            recencyScore = publicScores(i).publicUriScores.recencyScore,
            priorScore = priorScores(i),
            rekeepScore = publicScores(i).publicUriScores.rekeepScore,
            discoveryScore = publicScores(i).publicUriScores.discoveryScore,
            curationScore = publicScores(i).publicUriScores.curationScore,
            multiplier = Some(items(i).multiplier),
            libraryInducedScore = Some(libScores(i))
          )
          ScoredSeedItem(items(i).userId, items(i).uriId, scores)
        }
      }
    }
  }

}
