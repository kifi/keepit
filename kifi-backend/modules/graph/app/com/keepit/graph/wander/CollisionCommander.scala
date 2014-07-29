package com.keepit.graph.wander

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.graph.manager.GraphManager
import com.keepit.graph.model.VertexKind._
import com.keepit.graph.model._
import com.keepit.model.{ NormalizedURI, SocialUserInfo, User }

import scala.collection.mutable

class CollisionCommander @Inject() (graph: GraphManager, clock: Clock) extends Logging {

  def init(startingVertexKind: String, startingVertexId: Long, journal: TeleportationJournal, avoidFirstDegree: Boolean): Set[VertexId] = {
    val vertexKind = VertexKind(startingVertexKind)
    val vertexId = VertexId(vertexKind)(startingVertexId)
    if (avoidFirstDegree) getFirstDegreeNeighbors(vertexId, VertexKind.all) else Set(vertexId)
  }

  def getUsers(startingVertexKind: String, startingVertexId: Long, journal: TeleportationJournal, avoidFirstDegree: Boolean): Map[Id[User], Int] = {
    val collisionMap: Map[VertexId, Int] = journal.getVisited()
    val firstDegreeCollisions = init(startingVertexKind: String, startingVertexId: Long, journal: TeleportationJournal, avoidFirstDegree: Boolean)
    val users = mutable.Map[Id[User], Int]()
    collisionMap collect {
      case (vertexId, count) if count <= 1 || firstDegreeCollisions.contains(vertexId) => //igore
      case (vertexId, count) if vertexId.kind == UserReader => users += VertexDataId.toUserId(vertexId.asId[UserReader]) -> count
    }
    users.toMap
  }

  def getUris(startingVertexKind: String, startingVertexId: Long, journal: TeleportationJournal, avoidFirstDegree: Boolean): Map[Id[NormalizedURI], Int] = {
    val collisionMap: Map[VertexId, Int] = journal.getVisited()
    val firstDegreeCollisions = init(startingVertexKind: String, startingVertexId: Long, journal: TeleportationJournal, avoidFirstDegree: Boolean)
    val uris = mutable.Map[Id[NormalizedURI], Int]()
    collisionMap collect {
      case (vertexId, count) if count <= 1 || firstDegreeCollisions.contains(vertexId) => //igore
      case (vertexId, count) if vertexId.kind == UriReader => uris += VertexDataId.toNormalizedUriId(vertexId.asId[UriReader]) -> count
    }
    uris.toMap
  }

  def getSocialUsers(startingVertexKind: String, startingVertexId: Long, journal: TeleportationJournal, avoidFirstDegree: Boolean): Map[Id[SocialUserInfo], Int] = {
    val collisionMap: Map[VertexId, Int] = journal.getVisited()
    val firstDegreeCollisions = init(startingVertexKind: String, startingVertexId: Long, journal: TeleportationJournal, avoidFirstDegree: Boolean)
    val socialUsers = mutable.Map[Id[SocialUserInfo], Int]()
    collisionMap collect {
      case (vertexId, count) if count <= 1 || firstDegreeCollisions.contains(vertexId) => //igore
      case (vertexId, count) if vertexId.kind == FacebookAccountReader => socialUsers += VertexDataId.fromFacebookAccountIdtoSocialUserId(vertexId.asId[FacebookAccountReader]) -> count
      case (vertexId, count) if vertexId.kind == LinkedInAccountReader => socialUsers += VertexDataId.fromLinkedInAccountIdtoSocialUserId(vertexId.asId[LinkedInAccountReader]) -> count
    }
    socialUsers.toMap
  }

  private def collectNeighbors(vertexReader: GlobalVertexReader)(vertexId: VertexId, neighborKinds: Set[VertexType]): Set[VertexId] = {
    vertexReader.moveTo(vertexId)
    val neighbors = mutable.Set[VertexId]()
    while (vertexReader.outgoingEdgeReader.moveToNextComponent()) {
      val (_, destinationKind, _) = vertexReader.outgoingEdgeReader.component
      if (neighborKinds.contains(destinationKind)) {
        while (vertexReader.outgoingEdgeReader.moveToNextEdge()) {
          neighbors += vertexReader.outgoingEdgeReader.destination
        }
      }
    }
    neighbors.toSet
  }

  private def getFirstDegreeNeighbors(centralVertexId: VertexId, neighborKinds: Set[VertexType]): Set[VertexId] = {
    graph.readOnly { reader =>
      val vertexReader = reader.getNewVertexReader()
      val firstDegreeVertices = collectNeighbors(vertexReader)(centralVertexId, VertexKind.all)
      val firstDegreeUriOrSocialUsers = if (centralVertexId.kind != UserReader) Set.empty
      else {
        firstDegreeVertices.collect {
          case keep if keep.kind == KeepReader => collectNeighbors(vertexReader)(keep, Set(UriReader))
          case facebookSocial if facebookSocial.kind == FacebookAccountReader => collectNeighbors(vertexReader)(facebookSocial, Set(UserReader))
          case linkedInSocial if linkedInSocial.kind == LinkedInAccountReader => collectNeighbors(vertexReader)(linkedInSocial, Set(UserReader))
          //ignore other vertex types of neighbors
        }.flatten
      }
      firstDegreeVertices ++ firstDegreeUriOrSocialUsers + centralVertexId
    }
  }

}
