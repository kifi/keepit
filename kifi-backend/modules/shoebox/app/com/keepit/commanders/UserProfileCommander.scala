package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.AccessLog
import com.keepit.graph.GraphServiceClient
import com.keepit.model.{ UserConnectionRepo, User }
import com.keepit.social.BasicUser
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.Duration

case class ConnectedUserId(userId: Id[User], connected: Boolean)
object ConnectedUserId {
  implicit val formatter = Json.format[ConnectedUserId]
}

case class FollowerUserId(userId: Id[User], connected: Boolean)
object FollowerUserId {
  implicit val formatter = Json.format[FollowerUserId]
}

class UserProfileCommander @Inject() (
    db: Database,
    libraryCommander: LibraryCommander,
    userConnectionRepo: UserConnectionRepo,
    graphServiceClient: GraphServiceClient,
    implicit val defaultContext: ExecutionContext) {

  def getConnectionsSortedByRelationship(viewer: Id[User], owner: Id[User]): Future[Seq[ConnectedUserId]] = {
    val sociallyRelatedEntitiesF = graphServiceClient.getSociallyRelatedEntities(viewer)
    val connectionsF = db.readOnlyMasterAsync { implicit s =>
      val all = userConnectionRepo.getConnectedUsersForUsers(Set(viewer, owner)) //cached
      (all.getOrElse(viewer, Set.empty), all.getOrElse(owner, Set.empty))
    }
    for {
      sociallyRelatedEntities <- sociallyRelatedEntitiesF
      (viewerConnections, ownerConnections) <- connectionsF
    } yield {
      val relatedUsersMap = sociallyRelatedEntities.map(_.users.related).getOrElse(Seq.empty).toMap
      val ownerConnectionsMap = collection.mutable.Map(ownerConnections.toSeq.zip(List.fill(ownerConnections.size)(0d)): _*)
      //if its a mutual connection, set the relationship score to 100
      viewerConnections.filter(ownerConnectionsMap.contains).foreach { con =>
        ownerConnectionsMap(con) = 100d
      }
      relatedUsersMap.filter(t => ownerConnectionsMap.contains(t._1)).foreach {
        case (con, score) =>
          val newScore = ownerConnectionsMap(con) + score
          ownerConnectionsMap(con) = newScore
      }
      if (ownerConnectionsMap.contains(viewer)) {
        ownerConnectionsMap(viewer) = 10000d
      }
      val scoring = ownerConnectionsMap.toSeq.sortBy(_._2 * -1)
      val res = scoring.map(t => ConnectedUserId(t._1, t._1 == viewer || viewerConnections.contains(t._1)))
      res
    }
  }

  def getFollowersSortedByRelationship(viewerOpt: Option[Id[User]], owner: Id[User]): Future[Seq[FollowerUserId]] = {
    val sociallyRelatedEntitiesF = graphServiceClient.getSociallyRelatedEntities(viewerOpt.getOrElse(owner))
    val followersF = db.readOnlyReplicaAsync { implicit s =>
      libraryCommander.getFollowersByViewer(owner, viewerOpt)
    }
    val connectionsF: Future[Set[Id[User]]] = viewerOpt.map { viewer =>
      db.readOnlyReplicaAsync { implicit s =>
        userConnectionRepo.getConnectedUsers(viewer)
      }
    }.getOrElse(Future.successful(Set.empty))
    for {
      sociallyRelatedEntities <- sociallyRelatedEntitiesF
      followers <- followersF
      viewerConnections <- connectionsF
    } yield {
      val relatedUsersMap = sociallyRelatedEntities.map(_.users.related).getOrElse(Seq.empty).toMap
      val followersMap = collection.mutable.Map(followers.toSeq.zip(List.fill(followers.size)(0d)): _*)
      //if its a mutual connection, set the relationship score to 100
      viewerConnections.filter(followers.contains).foreach { con =>
        followersMap(con) = 100d
      }
      relatedUsersMap.filter(t => followersMap.contains(t._1)).foreach {
        case (con, score) =>
          val newScore = followersMap(con) + score
          followersMap(con) = newScore
      }
      viewerOpt.foreach { viewer =>
        if (followersMap.contains(viewer)) {
          followersMap(viewer) = 10000d
        }
      }
      val scoring = followersMap.toSeq.sortBy(_._2 * -1)
      scoring.map(t => FollowerUserId(t._1, viewerOpt.exists(_ == t._1) || viewerConnections.contains(t._1)))
    }
  }

}
