package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.cortex.CortexServiceClient
import com.keepit.curator.model.{ RecomputedScores, CuratorKeepInfoRepo, UriRecommendation }
import com.keepit.graph.GraphServiceClient
import com.keepit.heimdal.HeimdalServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

class UriRecosRescoringHelper @Inject() (
    graph: GraphServiceClient,
    keepInfoRepo: CuratorKeepInfoRepo,
    cortex: CortexServiceClient,
    heimdal: HeimdalServiceClient,
    db: Database) extends Logging {

  private def getRawInterestScores(items: Seq[UriRecommendation]): Future[(Seq[Float], Seq[Float])] = {
    val interestScores = cortex.batchUserURIsInterests(items.head.userId, items.map(_.uriId))
    interestScores.map { scores =>
      scores.map { score =>
        val (overallOpt, recentOpt) = (score.global, score.recency)
        (overallOpt.map(uis => if (uis.confidence > 0.5 && uis.score > 0) uis.score else 0.0).getOrElse(0.0).toFloat,
          recentOpt.map(uis => if (uis.confidence > 0.3 && uis.score > 0) uis.score else 0.0).getOrElse(0.0).toFloat)
      }.unzip
    }
  }

  private def getRawSocialScores(recos: Seq[UriRecommendation]): Future[Seq[Float]] = {
    //convert user scores seq to map, assume there is no duplicate userId from graph service
    graph.getConnectedUserScores(recos.head.userId, avoidFirstDegreeConnections = false).map { socialScores =>
      val socialScoreMap = socialScores.map { socialScore =>
        (socialScore.userId, socialScore.score.toFloat)
      }.toMap
      recos.map { reco =>
        var itemScore = 0.0f
        db.readOnlyMaster { implicit s =>
          keepInfoRepo.getKeepersByUriId(reco.uriId).map(userId =>
            itemScore += socialScoreMap.getOrElse(userId, 0.0f))
          Math.tanh(0.5 * itemScore).toFloat
        }
      }
    }.recover { //This needs to go once the graph is fixed
      case t: Throwable =>
        log.warn("Can't get social scores from graph.")
        Seq.fill[Float](recos.size)(0.0f)
    }
  }


  private def rescoreRecos(recos: Seq[UriRecommendation]): Future[Seq[RecomputedScores]] = {
    if (recos.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val socialScoresFuture = getRawSocialScores(recos)
      val interestScoresFuture = getRawInterestScores(recos)
      for {
        socialScores <- socialScoresFuture
        (overallInterestScores, recentInterestScores) <- interestScoresFuture
      } yield {
        for (i <- 0 until recos.length) yield {
          RecomputedScores(socialScores(i), overallInterestScores(i), recentInterestScores(i))
        }
      }
    }
  }

  def apply(recos: Seq[UriRecommendation]): Future[Seq[RecomputedScores]] = {
    rescoreRecos(recos)
  }
}
