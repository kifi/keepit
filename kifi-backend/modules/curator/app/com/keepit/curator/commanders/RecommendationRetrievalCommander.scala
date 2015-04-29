package com.keepit.curator.commanders

import com.keepit.common.db.Id
import com.keepit.curator.feedback.{ UserRecoFeedbackInferenceCommander, UserRecoFeedbackInferencer }
import com.keepit.curator.model._
import com.keepit.model.User
import com.keepit.common.db.slick.Database
import com.keepit.common.akka.SafeFuture

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.Random

import com.google.inject.{ Inject, Singleton }

case class UriRecoScore(score: Float, reco: UriRecommendation)

trait RecoSelectionStrategy {
  def sort(recosByTopScore: Seq[UriRecoScore]): Seq[UriRecoScore]
}

class TopScoreRecoSelectionStrategy(val minScore: Float = 0.5f) extends RecoSelectionStrategy {
  def sort(recosByTopScore: Seq[UriRecoScore]) =
    recosByTopScore filter (_.score > minScore) sortBy (-_.score)
}

class DiverseRecoSelectionStrategy(val minScore: Float = 0.5f) extends RecoSelectionStrategy {

  def sort(recosByTopScore: Seq[UriRecoScore]) = recosByTopScore.groupBy(_.reco.topic1).map {
    // sorts the recos by score (desc) and rescores them using the exponential decay
    case (topic1, recos) => rescoreRecosWithDecay(recos sortBy (-_.score) takeWhile (_.score > minScore)) map {
      case (recoScore, decay) =>
        // update the UriRecommendation.allScores to include topic and decay information
        val updatedAllScores = recoScore.reco.allScores.copy(topic1 = recoScore.reco.topic1.map(_.index), topic1Multiplier = Some(decay))
        val updatedReco = recoScore.reco.copy(allScores = updatedAllScores)
        recoScore.copy(reco = updatedReco)
    }
  }.toSeq.flatten sortBy (-_.score)

  private def rescoreRecosWithDecay(recoScores: Seq[UriRecoScore]): Seq[(UriRecoScore, Float)] = {
    val decayRate = 1 / 15f

    @inline def scoreDecay(i: Int): Float = Math.exp(-i * decayRate).toFloat

    Seq.tabulate(recoScores.size) { i =>
      val recoScore = recoScores(i)
      val decay = scoreDecay(i)
      (recoScore.copy(score = recoScore.score * decay), decay)
    }
  }
}

trait RecoScoringStrategy {
  def scoreItem(masterScore: Float, scores: UriScores, timesDelivered: Int, timesClicked: Int, goodBad: Option[Boolean], heavyPenalty: Boolean, recencyWeight: Float): Float = {
    val basePenaltyFactor = Math.pow(0.97, timesDelivered) * Math.pow(0.8, timesClicked)
    val votePenaltyFactor = goodBad.map { vote => if (vote) 0.97 else 0.5 }.getOrElse(1.0)
    val finalPenaltyFactor = Math.pow(basePenaltyFactor * votePenaltyFactor, if (heavyPenalty) 5 else 1)
    val adjustedScore = masterScore + recencyWeight * (4 * scores.recentInterestScore + 2 * scores.recencyScore)
    (adjustedScore * finalPenaltyFactor).toFloat
  }
}

class DefaultRecoScoringStrategy extends RecoScoringStrategy

class DumbRecoScoringStrategy extends RecoScoringStrategy {
  override def scoreItem(masterScore: Float, scores: UriScores, timesDelivered: Int, timesClicked: Int, goodBad: Option[Boolean], heavyPenalty: Boolean, recencyWeight: Float): Float = {
    val basePenaltyFactor = Math.pow(0.97, timesDelivered) * Math.pow(0.8, timesClicked)
    val votePenaltyFactor = goodBad.map { vote => if (vote) 0.97 else 0.5 }.getOrElse(1.0)
    val finalPenaltyFactor = Math.pow(basePenaltyFactor * votePenaltyFactor, if (heavyPenalty) 5 else 1)
    finalPenaltyFactor.toFloat * masterScore
  }
}

class NonLinearRecoScoringStrategy extends RecoScoringStrategy {
  private[this] val rnd = new Random

  override def scoreItem(masterScore: Float, scores: UriScores, timesDelivered: Int, timesClicked: Int, goodBad: Option[Boolean], heavyPenalty: Boolean, recencyWeight: Float): Float = {
    super.scoreItem(recomputeScore(scores), scores, timesDelivered, timesClicked, goodBad, heavyPenalty, recencyWeight)
  }

  private def recomputeScore(scores: UriScores): Float = {
    val interestPart = (
      5 * scores.overallInterestScore +
      7 * scores.recentInterestScore +
      4 * scores.libraryInducedScore.getOrElse(0.0f)
    )
    val factor = if (rnd.nextDouble() > 0.1d) {
      val normalizer = (5f + 7f + scores.libraryInducedScore.fold(0f)(_ => 4f))
      interestPart / normalizer
    } else {
      1.0f
    }

    val socialPart = (
      4 * scores.socialScore +
      2 * scores.priorScore +
      1 * scores.recencyScore +
      1 * scores.popularityScore +
      6 * scores.rekeepScore +
      3 * scores.discoveryScore +
      4 * scores.curationScore.getOrElse(0.0f)
    )

    (interestPart + factor * socialPart) * scores.multiplier.getOrElse(1.0f)
  }
}

@Singleton
class RecommendationRetrievalCommander @Inject() (
    seedCommander: SeedIngestionCommander,
    db: Database,
    uriRecoRepo: UriRecommendationRepo,
    analytics: CuratorAnalytics,
    publicFeedRepo: PublicFeedRepo,
    feedbackInferenceCommander: UserRecoFeedbackInferenceCommander) {

  val idFilter = new RecoIdFilter[UriRecoScore] {}

  def topRecos(userId: Id[User], more: Boolean = false, recencyWeight: Float = 0.5f, source: RecommendationSource, subSource: RecommendationSubSource, recoSortStrategy: RecoSelectionStrategy, scoringStrategy: RecoScoringStrategy, context: Option[String] = None, applyFeedback: Boolean = false): URIRecoResults = {
    require(recencyWeight <= 1.0f && recencyWeight >= 0.0f, "recencyWeight must be between 0 and 1")

    def scoreReco(reco: UriRecommendation) =
      UriRecoScore(scoringStrategy.scoreItem(reco.masterScore, reco.allScores, reco.delivered, reco.clicked, reco.vote, more, recencyWeight), reco)

    val (recos, newContext) = db.readOnlyReplica { implicit session =>
      val recosByTopScore = uriRecoRepo.getRecommendableByTopMasterScore(userId, 1000) map scoreReco
      val scoreWithFeedback = if (applyFeedback) feedbackInferenceCommander.applyMultipliers(userId, recosByTopScore) else recosByTopScore
      val (accepted, _) = idFilter.filter(scoreWithFeedback, context)((x: UriRecoScore) => x.reco.uriId.id)
      val finalSorted = recoSortStrategy.sort(accepted)
      idFilter.take(finalSorted, context, limit = 10)((x: UriRecoScore) => x.reco.uriId.id)
    }

    SafeFuture {
      analytics.trackDeliveredItems(recos.map(_.reco), Some(source), Some(subSource))
      db.readWrite { implicit session =>
        recos.map { recoScore =>
          uriRecoRepo.incrementDeliveredCount(recoScore.reco.id.get)
        }
      }
    }

    val recosInfo = recos.map {
      case UriRecoScore(score, reco) =>
        RecoInfo(
          userId = Some(reco.userId),
          uriId = reco.uriId,
          score = scoringStrategy.scoreItem(reco.masterScore, reco.allScores, reco.delivered, reco.clicked, reco.vote, more, recencyWeight),
          explain = Some(reco.allScores.toString),
          attribution = Some(reco.attribution)
        )
    }

    URIRecoResults(recosInfo, newContext)

  }

  def topPublicRecos(userIdOpt: Option[Id[User]]): Seq[RecoInfo] = {
    val candidates = db.readOnlyReplica { implicit session =>
      publicFeedRepo.getByTopMasterScore(1000)
    }.sortBy(-1 * _.updatedAt.getMillis).take(100)

    Random.shuffle(candidates).iterator.filter { feed =>
      userIdOpt.forall { userId =>
        seedCommander.getPublicSeedItem(feed.uriId).exists { seed =>
          seed.keepers match {
            case Keepers.ReasonableNumber(users) => !users.contains(userId)
            case _ => false
          }
        }
      }
    }.take(10).map { reco =>
      RecoInfo(
        userId = None,
        uriId = reco.uriId,
        score = reco.publicMasterScore,
        explain = None,
        attribution = None
      )
    }.toSeq
  }

  def generalRecos(): Seq[RecoInfo] = {
    db.readOnlyReplica { implicit session =>
      publicFeedRepo.getByTopMasterScore(100)
    } map { reco =>
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
