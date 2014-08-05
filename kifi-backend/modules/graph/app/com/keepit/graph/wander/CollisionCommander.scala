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
import com.keepit.graph.utils.GraphPrimitives

class CollisionCommander @Inject() (graph: GraphManager, clock: Clock) extends Logging {

  def getUsers(startingVertexId: VertexId, journal: TeleportationJournal, avoidFirstDegree: Boolean): Map[Id[User], Int] = {
    val collisionMap: Map[VertexId, Int] = journal.getVisited()
    val avoidedVertices = getVerticesToAvoid(startingVertexId, avoidFirstDegree)
    val users = mutable.Map[Id[User], Int]()
    collisionMap collect {
      case (vertexId, count) if count <= 1 || avoidedVertices.contains(vertexId) => //igore
      case (vertexId, count) if vertexId.kind == UserReader => users += VertexDataId.toUserId(vertexId.asId[UserReader]) -> count
    }
    users.toMap
  }

  def getUris(startingVertexId: VertexId, journal: TeleportationJournal, avoidFirstDegree: Boolean): Map[Id[NormalizedURI], Int] = {
    val collisionMap: Map[VertexId, Int] = journal.getVisited()
    val avoidedVertices = getVerticesToAvoid(startingVertexId, avoidFirstDegree)
    val uris = mutable.Map[Id[NormalizedURI], Int]()
    collisionMap collect {
      case (vertexId, count) if count <= 1 || avoidedVertices.contains(vertexId) => //igore
      case (vertexId, count) if vertexId.kind == UriReader => uris += VertexDataId.toNormalizedUriId(vertexId.asId[UriReader]) -> count
    }
    uris.toMap
  }

  def getSocialUsers(startingVertexId: VertexId, journal: TeleportationJournal, avoidFirstDegree: Boolean): Map[Id[SocialUserInfo], Int] = {
    val collisionMap: Map[VertexId, Int] = journal.getVisited()
    val avoidedVertices = getVerticesToAvoid(startingVertexId, avoidFirstDegree)
    val socialUsers = mutable.Map[Id[SocialUserInfo], Int]()
    collisionMap collect {
      case (vertexId, count) if count <= 1 || avoidedVertices.contains(vertexId) => //igore
      case (vertexId, count) if vertexId.kind == FacebookAccountReader => socialUsers += VertexDataId.fromFacebookAccountIdtoSocialUserId(vertexId.asId[FacebookAccountReader]) -> count
      case (vertexId, count) if vertexId.kind == LinkedInAccountReader => socialUsers += VertexDataId.fromLinkedInAccountIdtoSocialUserId(vertexId.asId[LinkedInAccountReader]) -> count
    }
    socialUsers.toMap
  }

  private def getVerticesToAvoid(startingVertexId: VertexId, avoidFirstDegree: Boolean): Set[VertexId] = {
    if (avoidFirstDegree) {
      graph.readOnly { reader =>
        val vertexReader = reader.getNewVertexReader()
        val collectNeighbors = GraphPrimitives.collectOutgoingNeighbors(vertexReader) _
        val firstDegreeVertices = collectNeighbors(startingVertexId, VertexKind.all)
        val firstDegreeUriAndSocialUsers = if (startingVertexId.kind != UserReader) Set.empty
        else {
          firstDegreeVertices.collect {
            case keep if keep.kind == KeepReader => collectNeighbors(keep, Set(UriReader))
            case facebookSocial if facebookSocial.kind == FacebookAccountReader => collectNeighbors(facebookSocial, Set(UserReader))
            case linkedInSocial if linkedInSocial.kind == LinkedInAccountReader => collectNeighbors(linkedInSocial, Set(UserReader))
            //ignore other vertex types of neighbors
          }.flatten
        }
        firstDegreeVertices ++ firstDegreeUriAndSocialUsers + startingVertexId
      }
    } else {
      Set(startingVertexId)
    }
  }

}
