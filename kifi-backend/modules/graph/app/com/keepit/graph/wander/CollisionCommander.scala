package com.keepit.graph.wander

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.graph.manager.GraphManager
import com.keepit.graph.model.VertexKind._
import com.keepit.graph.model._
import com.keepit.model.{ NormalizedURI, SocialUserInfo, User }

import scala.collection.mutable

class CollisionCommander @Inject() (graph: GraphManager, clock: Clock) extends Logging {

  def getUsers(startingVertexKind: String, startingVertexId: Long, journal: TeleportationJournal, avoidFirstDegree: Boolean): Map[Id[User], Int] = {
    val collisionMap: Map[VertexId, Int] = journal.getVisited()
    val vertexKind = VertexKind(startingVertexKind)
    val vertexId = VertexId(vertexKind)(startingVertexId)
    val firstDegreeCollisions = if (avoidFirstDegree) getFirstDegreeNeighbors(vertexId, vertexKind) else Set(vertexId)
    val users = mutable.Map[Id[User], Int]()
    collisionMap collect {
      case (vertexId, count) if count <= 1 || firstDegreeCollisions.contains(vertexId) => //igore
      case (vertexId, count) if vertexId.kind == UserReader => users += VertexDataId.toUserId(vertexId.asId[UserReader]) -> count
    }
    users.toMap
  }

  def getUris(startingVertexKind: String, startingVertexId: Long, journal: TeleportationJournal, avoidFirstDegree: Boolean): Map[Id[NormalizedURI], Int] = {
    val collisionMap: Map[VertexId, Int] = journal.getVisited()
    val vertexKind = VertexKind.apply(startingVertexKind)
    val vertexId = VertexId(vertexKind)(startingVertexId)
    val firstDegreeCollisions = if (avoidFirstDegree) getFirstDegreeNeighbors(vertexId, vertexKind) else Set(vertexId)
    val uris = mutable.Map[Id[NormalizedURI], Int]()
    collisionMap collect {
      case (vertexId, count) if count <= 1 || firstDegreeCollisions.contains(vertexId) => //igore
      case (vertexId, count) if vertexId.kind == UriReader => uris += VertexDataId.toNormalizedUriId(vertexId.asId[UriReader]) -> count
    }
    uris.toMap
  }

  def getSocialUsers(startingVertexKind: String, startingVertexId: Long, journal: TeleportationJournal, avoidFirstDegree: Boolean): Map[Id[SocialUserInfo], Int] = {
    val collisionMap: Map[VertexId, Int] = journal.getVisited()
    val vertexKind = VertexKind.apply(startingVertexKind)
    val vertexId = VertexId(vertexKind)(startingVertexId)
    val firstDegreeCollisions = if (avoidFirstDegree) getFirstDegreeNeighbors(vertexId, vertexKind) else Set(vertexId)
    val socialUsers = mutable.Map[Id[SocialUserInfo], Int]()
    collisionMap collect {
      case (vertexId, count) if count <= 1 || firstDegreeCollisions.contains(vertexId) => //igore
      case (vertexId, count) if vertexId.kind == FacebookAccountReader => socialUsers += VertexDataId.fromFacebookAccountIdtoSocialUserId(vertexId.asId[FacebookAccountReader]) -> count
      case (vertexId, count) if vertexId.kind == LinkedInAccountReader => socialUsers += VertexDataId.fromLinkedInAccountIdtoSocialUserId(vertexId.asId[LinkedInAccountReader]) -> count
    }
    socialUsers.toMap
  }

  private def collectNeighbors(vertexReader: GlobalVertexReader)(vertexId: VertexId, neighborKind: VertexType): Set[VertexId] = {
    vertexReader.moveTo(vertexId)
    val neighbors = mutable.Set[VertexId]()
    while (vertexReader.edgeReader.moveToNextComponent()) {
      val (destinationKind, _) = vertexReader.edgeReader.component
      if (neighborKind == destinationKind) {
        while (vertexReader.edgeReader.moveToNextEdge()) { neighbors += vertexReader.edgeReader.destination }
      }
    }
    neighbors.toSet
  }

  private def getFirstDegreeNeighbors(centralVertexId: VertexId, vertexType: VertexType): Set[VertexId] = {
    val firstDegreeNeighbors = graph.readOnly { reader =>
      val vertexReader = reader.getNewVertexReader()
      val firstDegree = collectNeighbors(vertexReader)(centralVertexId, vertexType.kind)
      firstDegree + centralVertexId
    }

    firstDegreeNeighbors
  }

}
