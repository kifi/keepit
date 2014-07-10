package com.keepit.graph.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Shoebox
import com.keepit.graph.model.{UriScoreData, UserScoreData, UserScoreCacheKey, UserScoreCache}
import com.keepit.graph.wander.{Wanderlust, WanderingCommander}
import com.keepit.model.{NormalizedURI, User}
import play.api.libs.json.JsArray
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

class GraphCommander @Inject() (
  wanderingCommander: WanderingCommander,
  userScoreCache: UserScoreCache
) extends Logging {

  def getListOfUriAndScorePairs(userId:Id[User]) = {
    val wanderlust = Wanderlust.discovery(userId)
    val collisions = wanderingCommander.wander(wanderlust)
    val urisMap: Map[Id[NormalizedURI], Int] = collisions.uris
    urisMap.toList.sortBy(_._2).take(500).map { case (uriId, score) =>
      UriScoreData(uriId, score)
    }
  }

  def getListOfUserAndScorePairs(userId:Id[User]) = {
    userScoreCache.getOrElse(UserScoreCacheKey(userId)) {
      val wanderlust = Wanderlust.discovery(userId)
      val collisions = wanderingCommander.wander(wanderlust)
      val usersMap: Map[Id[User], Int] = collisions.users
      usersMap.toList.sortBy(_._2).take(500).map { case (userId, score) =>
        UserScoreData(userId, score)
      }
    }
  }
}
