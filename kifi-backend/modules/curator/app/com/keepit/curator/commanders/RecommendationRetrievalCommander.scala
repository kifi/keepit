package com.keepit.curator.commanders

import com.keepit.common.db.Id
import com.keepit.curator.model.{ RecoInfo, RecommendationClientType, UriScores, PublicFeedRepo, UriRecommendationRepo, UriRecommendation }
import com.keepit.model.User
import com.keepit.common.db.slick.Database
import com.keepit.common.akka.SafeFuture

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.Random

import com.google.inject.{ Inject, Singleton }

case class UriRecoScore(score: Float, reco: UriRecommendation)

trait RecoSortStrategy {
  def sort(recosByTopScore: Seq[UriRecoScore]): Seq[UriRecoScore]
}

class TopScoreRecoSortStrategy(val minScore: Float = 5f, val limit: Int = 10) extends RecoSortStrategy {
  def sort(recosByTopScore: Seq[UriRecoScore]) =
    recosByTopScore filter (_.score > minScore) sortBy (-_.score) take limit
}

class DiverseRecoSortStrategy(val limit: Int = 10) extends RecoSortStrategy {

  def sort(recosByTopScore: Seq[UriRecoScore]) = recosByTopScore.groupBy(_.reco.topic1).map {
    // sorts the recos by score (desc) and rescores them using the exponential decay
    case (_, recos) => rescoreRecosWithDecay(recos sortBy (-_.score) take limit)
  }.toSeq.flatten sortBy (-_.score) take limit

  private def rescoreRecosWithDecay(recoScores: Seq[UriRecoScore]) = {
    val decayRate = 1 / 15f

    @inline def scoreDecay(i: Int): Float = Math.exp(-i * decayRate).toFloat

    Seq.tabulate(recoScores.size) { i =>
      val recoScore = recoScores(i)
      recoScore.copy(score = recoScore.score * scoreDecay(i))
    }
  }
}

@Singleton
class RecommendationRetrievalCommander @Inject() (db: Database, uriRecoRepo: UriRecommendationRepo, analytics: CuratorAnalytics, publicFeedRepo: PublicFeedRepo) {

  private def scoreItem(masterScore: Float, scores: UriScores, timesDelivered: Int, timesClicked: Int, goodBad: Option[Boolean], heavyPenalty: Boolean, recencyWeight: Float): Float = {
    val basePenaltyFactor = Math.pow(0.97, timesDelivered) * Math.pow(0.8, timesClicked)
    val votePenaltyFactor = goodBad.map { vote => if (vote) 0.97 else 0.5 }.getOrElse(1.0)
    val finalPenaltyFactor = Math.pow(basePenaltyFactor * votePenaltyFactor, if (heavyPenalty) 5 else 1)
    val adjustedScore = masterScore + recencyWeight * (4 * scores.recentInterestScore + 2 * scores.recencyScore)
    (adjustedScore * finalPenaltyFactor).toFloat
  }

  def topRecos(userId: Id[User], more: Boolean = false, recencyWeight: Float = 0.5f, clientType: RecommendationClientType, recoSortStrategy: RecoSortStrategy): Seq[RecoInfo] = {
    require(recencyWeight <= 1.0f && recencyWeight >= 0.0f, "recencyWeight must be between 0 and 1")

    def scoreReco(reco: UriRecommendation) =
      UriRecoScore(scoreItem(reco.masterScore, reco.allScores, reco.delivered, reco.clicked, reco.vote, more, recencyWeight), reco)

    val recos = db.readOnlyReplica { implicit session =>
      val recosByTopScore = uriRecoRepo.getRecommendableByTopMasterScore(userId, 1000) map scoreReco
      recoSortStrategy.sort(recosByTopScore)
    }

    SafeFuture {
      analytics.trackDeliveredItems(recos.map(_.reco), Some(clientType))
      db.readWrite { implicit session =>
        recos.map { recoScore =>
          uriRecoRepo.incrementDeliveredCount(recoScore.reco.id.get)
        }
      }
    }

    recos.map {
      case UriRecoScore(score, reco) =>
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
    val candidates = db.readOnlyReplica { implicit session =>
      publicFeedRepo.getByTopMasterScore(1000)
    }.sortBy(-1 * _.updatedAt.getMillis).take(100)

    Random.shuffle(candidates).take(10).map { reco =>
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
