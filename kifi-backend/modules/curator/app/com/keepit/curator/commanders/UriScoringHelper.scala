package com.keepit.curator.commanders

import com.keepit.common.db.Id
import com.keepit.curator.model.{ CuratorKeepInfoRepo, Keepers, SeedItem, ScoredSeedItem }

import com.google.inject.{ Inject, Singleton }
import com.keepit.graph.GraphServiceClient
import com.keepit.model.User

import scala.concurrent.Future

@Singleton
class UriScoringHelper @Inject() (
  graph: GraphServiceClient,
  keepInfoRepo: CuratorKeepInfoRepo) {

  //var socialScoresCache = Map[Id[User],Future[Seq[Float]]]()

  //      socialScoresCache.getOrElse(userId, graph.getConnectedUserScores(userId, avoidFirstDegreeConnections = false).map { connectedUserScores =>
  //        val socialScores = connectedUserScores.map { connectedUserScore =>
  //          connectedUserScore.score.toFloat
  //        }
  //        socialScoresCache += userId -> socialScores
  //        socialScores
  //      })

  // assume all items have same userId
  def getSocialScores(items: Seq[SeedItem]): Future[Seq[Float]] = {
    if (items.isEmpty) {
      Future.successful(Seq.empty)
    } else {

      //convert user scores seq to map, assume there is no dupicate userId from graph service
      graph.getConnectedUserScores(items.head.userId, avoidFirstDegreeConnections = false).map { socialScores =>
        val socialScoreMap = socialScores.map { socialScore =>
          (socialScore.userId, socialScore.score.toFloat)
        }.toMap

        items.map(item =>
          item.keepers match {
            case Keepers.TooMany => 0.0f
            case Keepers.ReasonableNumber => {
              var itemScore = 0.0f
              keepInfoRepo.getKeepersByUriId(item.uriId).map(userId => itemScore += socialScoreMap.getOrElse(userId, 0.0f))
              itemScore
            }
          })
      }
    }
  }

  def apply(items: Seq[SeedItem]): Seq[ScoredSeedItem] = ???

}
