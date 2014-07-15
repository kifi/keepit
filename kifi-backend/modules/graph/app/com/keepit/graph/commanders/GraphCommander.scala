package com.keepit.graph.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.graph.model._
import com.keepit.graph.wander._
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

@Singleton
class GraphCommander @Inject() (
    wanderingCommander: WanderingCommander,
    collisionCommander: CollisionCommander,
    userScoreCache: UserConnectionSocialScoreCache,
    uriScoreCache: UserConnectionFeedScoreCache) extends Logging {
  private val maxResults = 500

  private def getUriScoreList(vertexKind: String, vertexId: Long, journal: TeleportationJournal): Seq[UserConnectionFeedScore] = {
    val ls = collisionCommander.getUris(vertexKind, vertexId, journal, true).toList.sortBy(_._2)
    ls.take(maxResults).map {
      case (uriId, count) =>
        UserConnectionFeedScore(uriId, count / ls.head._2)
    }
  }

  private def getUsersScoreList(vertexKind: String, vertexId: Long, journal: TeleportationJournal): Seq[UserConnectionSocialScore] = {
    val ls = collisionCommander.getUsers(vertexKind, vertexId, journal, false).toList.sortBy(_._2)
    ls.take(maxResults).map {
      case (userId, count) =>
        UserConnectionSocialScore(userId, count / ls.head._2)
    }
  }

  private def updateScoreCaches(userId: Id[User], vertexKind: String, vertexId: Long, journal: TeleportationJournal): (Seq[UserConnectionFeedScore], Seq[UserConnectionSocialScore]) = {
    val urisList = getUriScoreList(vertexKind, vertexId, journal)
    val usersList = getUsersScoreList(vertexKind, vertexId, journal)
    uriScoreCache.set(UserConnectionFeedScoreCacheKey(userId), urisList)
    userScoreCache.set(UserConnectionSocialScoreCacheKey(userId), usersList)
    (urisList, usersList)
  }

  def getListOfUriAndScorePairs(userId: Id[User]): Seq[UserConnectionFeedScore] = {
    val wanderLust = Wanderlust.discovery(userId)
    val journal = wanderingCommander.wander(wanderLust)

    val result = uriScoreCache.get(UserConnectionFeedScoreCacheKey(userId))
    result match {
      case None => updateScoreCaches(userId, wanderLust.startingVertexKind, wanderLust.startingVertexDataId, jounal)._1
      case _ => result.get
    }
  }

  def getListOfUserAndScorePairs(userId: Id[User]): Seq[UserConnectionSocialScore] = {
    val wanderLust = Wanderlust.discovery(userId)
    val journal = wanderingCommander.wander(wanderLust)

    val result = userScoreCache.get(UserConnectionSocialScoreCacheKey(userId))
    result match {
      case None => updateScoreCaches(userId, wanderLust.startingVertexKind, wanderLust.startingVertexDataId, jounal)._2
      case _ => result.get
    }
  }

}
