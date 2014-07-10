package com.keepit.graph.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.routes.Shoebox
import com.keepit.graph.model._
import com.keepit.graph.wander.{Collisions, Wanderlust, WanderingCommander}
import com.keepit.model.{NormalizedURI, User}
import play.api.libs.json.JsArray
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

import scala.collection.mutable
import scala.concurrent.duration._

class GraphCommander @Inject() (
  wanderingCommander: WanderingCommander,
  userScoreCache: UserConnectionSocialScoreCache,
  uriScoreCache: UserConnectionFeedScoreCache
) extends Logging {
  val maxResults = 500

  private def getUriScoreList(collisions: Collisions): List[UserConnectionFeedScore] = {
    val ls = collisions.uris.toList
    ls.sortBy(_._2).take(maxResults).map { case (uriId, count) =>
      UserConnectionFeedScore(uriId, count / ls.max._2)
    }
  }

  private def getUsersScoreList(collisions: Collisions): List[UserConnectionSocialScore] = {
    val ls = collisions.users.toList
    ls.sortBy(_._2).take(maxResults).map { case (userId, count) =>
      UserConnectionSocialScore(userId, count / ls.max._2)
    }
  }

  private def updateScoreCaches(userId: Id[User]): (List[UserConnectionFeedScore], List[UserConnectionSocialScore]) = {
    val wanderlust = Wanderlust.discovery(userId)
    val collisions = wanderingCommander.wander(wanderlust)
    val usersList = getUsersScoreList(collisions)
    val urisList = getUriScoreList(collisions)
    uriScoreCache.set(UserConnectionFeedScoreCacheKey(userId), urisList)
    userScoreCache.set(UserConnectionSocialScoreCacheKey(userId), usersList)
    (urisList, usersList)
  }

  def getListOfUriAndScorePairs(userId:Id[User]) = {
    uriScoreCache.get(UserConnectionFeedScoreCacheKey(userId)) match {
      case None => updateScoreCaches(userId)._1
      case _ => _
    }
  }

  def getListOfUserAndScorePairs(userId:Id[User]) = {
    userScoreCache.get(UserConnectionSocialScoreCacheKey(userId)) match {
      case None => updateScoreCaches(userId)._2
      case _ => _
    }
  }

}
