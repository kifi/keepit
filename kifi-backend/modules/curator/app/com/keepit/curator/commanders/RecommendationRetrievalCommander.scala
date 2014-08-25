package com.keepit.curator.commanders

import com.keepit.curator.model.{ RecommendationClientType, RecoInfo, UriRecommendationRepo, UriScores, PublicFeedRepo }
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.common.db.slick.Database
import com.keepit.common.akka.SafeFuture

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.{ Inject, Singleton }

@Singleton
class RecommendationRetrievalCommander @Inject() (db: Database, uriRecoRepo: UriRecommendationRepo, analytics: CuratorAnalytics, publicFeedRepo: PublicFeedRepo) {

  private def scoreItem(masterScore: Float, scores: UriScores, timesDelivered: Int, timesClicked: Int, goodBad: Option[Boolean], heavyPenalty: Boolean, recencyWeight: Float): Float = {
    val basePenaltyFactor = Math.pow(0.95, timesDelivered) * Math.pow(0.8, timesClicked)
    val votePenaltyFactor = goodBad.map { vote => if (vote) 0.95 else 0.5 }.getOrElse(1.0)
    val finalPenaltyFactor = Math.pow(basePenaltyFactor * votePenaltyFactor, if (heavyPenalty) 5 else 1)
    val adjustedScore = masterScore + recencyWeight * (4 * scores.recentInterestScore + 2 * scores.recencyScore)
    (adjustedScore * finalPenaltyFactor).toFloat
  }

  def topRecos(userId: Id[User], more: Boolean = false, recencyWeight: Float = 0.5f, clientType: RecommendationClientType): Seq[RecoInfo] = {
    require(recencyWeight <= 1.0f && recencyWeight >= 0.0f, "recencyWeight must be between 0 and 1")

    val recos = db.readOnlyReplica { implicit session =>
      uriRecoRepo.getRecommendableByTopMasterScore(userId, 1000)
    } map { reco =>
      (scoreItem(reco.masterScore, reco.allScores, reco.delivered, reco.clicked, reco.vote, more, recencyWeight), reco)
    } filter (_._1 > 1.0f) sortBy (-1 * _._1) take 10

    SafeFuture {
      analytics.trackDeliveredItems(recos.map(_._2), Some(clientType))
    }

    recos.map {
      case (score, reco) =>
        RecoInfo(
          userId = Some(reco.userId),
          uriId = reco.uriId,
          score = scoreItem(reco.masterScore, reco.allScores, reco.delivered, reco.clicked, reco.vote, more, recencyWeight),
          explain = Some(reco.allScores.toString),
          attribution = Some(reco.attribution)
        )
    }

  }

  def topPublicRecos(): Seq[RecoInfo] = {
    db.readOnlyReplica { implicit session => publicFeedRepo.getByTopMasterScore(1000) }.sortBy(-1 * _.updatedAt.getMillis).take(10).map { reco =>
      RecoInfo(
        userId = None,
        uriId = reco.uriId,
        score = reco.publicMasterScore,
        explain = None,
        attribution = None
      )
    }
  }

}
