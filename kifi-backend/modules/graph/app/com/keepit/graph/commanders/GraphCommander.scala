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
    userScoreCache: ConnectedUserScoreCache,
    uriScoreCache: ConnectedUriScoreCache) extends Logging {
  private val maxResults = 500

  private def getUriScoreList(vertexKind: String, vertexId: Long, journal: TeleportationJournal, avoidFirstDegreeConnections: Boolean): Seq[ConnectedUriScore] = {
    val ls = collisionCommander.getUris(vertexKind, vertexId, journal, avoidFirstDegreeConnections).toList.sortBy(_._2)
    ls.take(maxResults).map {
      case (uriId, count) =>
        ConnectedUriScore(uriId, count / ls.head._2)
    }
  }

  private def getUsersScoreList(vertexKind: String, vertexId: Long, journal: TeleportationJournal, avoidFirstDegreeConnections: Boolean): Seq[ConnectedUserScore] = {
    val ls = collisionCommander.getUsers(vertexKind, vertexId, journal, avoidFirstDegreeConnections).toList.sortBy(_._2)
    ls.take(maxResults).map {
      case (userId, count) =>
        ConnectedUserScore(userId, count / ls.head._2)
    }
  }

  private def updateScoreCaches(userId: Id[User], vertexKind: String, vertexId: Long, journal: TeleportationJournal, avoidFirstDegreeConnection: Boolean): (Seq[ConnectedUriScore], Seq[ConnectedUserScore]) = {
    val urisList = getUriScoreList(vertexKind, vertexId, journal, avoidFirstDegreeConnection)
    val usersList = getUsersScoreList(vertexKind, vertexId, journal, avoidFirstDegreeConnection)
    uriScoreCache.set(ConnectedUriScoreCacheKey(userId, avoidFirstDegreeConnection), urisList)
    userScoreCache.set(ConnectedUserScoreCacheKey(userId, avoidFirstDegreeConnection), usersList)
    (urisList, usersList)
  }

  def getListOfUriAndScorePairs(userId: Id[User], avoidFirstDegreeConnections: Boolean): Seq[ConnectedUriScore] = {
    val wanderLust = Wanderlust.discovery(userId)
    val journal = wanderingCommander.wander(wanderLust)

    val result = uriScoreCache.get(ConnectedUriScoreCacheKey(userId, avoidFirstDegreeConnections))
    result match {
      case None => updateScoreCaches(userId, wanderLust.startingVertexKind, wanderLust.startingVertexDataId, journal, avoidFirstDegreeConnections)._1
      case Some(data) => data
    }
  }

  def getListOfUserAndScorePairs(userId: Id[User], avoidFirstDegreeConnections: Boolean): Seq[ConnectedUserScore] = {
    val wanderLust = Wanderlust.discovery(userId)
    val journal = wanderingCommander.wander(wanderLust)

    val result = userScoreCache.get(ConnectedUserScoreCacheKey(userId, avoidFirstDegreeConnections))
    result match {
      case None => updateScoreCaches(userId, wanderLust.startingVertexKind, wanderLust.startingVertexDataId, journal, avoidFirstDegreeConnections)._2
      case Some(data) => data
    }
  }

}
