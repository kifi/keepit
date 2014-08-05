package com.keepit.graph.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.graph.model._
import com.keepit.graph.wander._
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton
class GraphCommander @Inject() (
    wanderingCommander: WanderingCommander,
    collisionCommander: CollisionCommander,
    userScoreCache: ConnectedUserScoreCache,
    uriScoreCache: ConnectedUriScoreCache) extends Logging {
  private val maxResults = 500

  private def getUriScoreList(startingVertexId: VertexId, journal: TeleportationJournal, avoidFirstDegreeConnections: Boolean): Seq[ConnectedUriScore] = {
    val ls = collisionCommander.getUris(startingVertexId, journal, avoidFirstDegreeConnections).toList.sortBy(-_._2)
    ls.take(maxResults).map {
      case (uriId, count) =>
        ConnectedUriScore(uriId, count.toDouble / ls.head._2)
    }
  }

  private def getUsersScoreList(startingVertexId: VertexId, journal: TeleportationJournal, avoidFirstDegreeConnections: Boolean): Seq[ConnectedUserScore] = {
    val ls = collisionCommander.getUsers(startingVertexId, journal, avoidFirstDegreeConnections).toList.sortBy(-_._2)
    ls.take(maxResults).map {
      case (userId, count) =>
        ConnectedUserScore(userId, count.toDouble / ls.head._2)
    }
  }

  private def updateScoreCaches(userId: Id[User], startingVertexId: VertexId, journal: TeleportationJournal, avoidFirstDegreeConnection: Boolean): (Seq[ConnectedUriScore], Seq[ConnectedUserScore]) = {
    val urisList = getUriScoreList(startingVertexId, journal, avoidFirstDegreeConnection)
    val usersList = getUsersScoreList(startingVertexId, journal, avoidFirstDegreeConnection)
    uriScoreCache.set(ConnectedUriScoreCacheKey(userId, avoidFirstDegreeConnection), urisList)
    userScoreCache.set(ConnectedUserScoreCacheKey(userId, avoidFirstDegreeConnection), usersList)
    (urisList, usersList)
  }

  def getConnectedUriScores(userId: Id[User], avoidFirstDegreeConnections: Boolean): Future[Seq[ConnectedUriScore]] = {
    val result = uriScoreCache.get(ConnectedUriScoreCacheKey(userId, avoidFirstDegreeConnections))
    result match {
      case None => {
        val wanderLust = Wanderlust.discovery(userId)
        wanderingCommander.wander(wanderLust).map { journal =>
          updateScoreCaches(userId, journal.getStartingVertex, journal, avoidFirstDegreeConnections)._1
        }
      }
      case Some(data) => Future.successful(data)
    }
  }

  def getConnectedUserScores(userId: Id[User], avoidFirstDegreeConnections: Boolean): Future[Seq[ConnectedUserScore]] = {
    val result = userScoreCache.get(ConnectedUserScoreCacheKey(userId, avoidFirstDegreeConnections))
    result match {
      case None => {
        val wanderLust = Wanderlust.discovery(userId)
        wanderingCommander.wander(wanderLust).map { journal =>
          updateScoreCaches(userId, journal.getStartingVertex, journal, avoidFirstDegreeConnections)._2
        }
      }
      case Some(data) => Future.successful(data)
    }
  }

}
