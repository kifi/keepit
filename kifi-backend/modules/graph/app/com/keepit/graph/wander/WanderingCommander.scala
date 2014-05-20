package com.keepit.graph.wander

import com.google.inject.{Singleton, Inject}
import com.keepit.graph.manager.GraphManager
import com.keepit.graph.model._
import com.keepit.model.{NormalizedURI, User}
import com.keepit.common.db.Id
import scala.collection.mutable
import com.keepit.graph.model.VertexKind.VertexType

@Singleton
class WanderingCommander @Inject() (graph: GraphManager) {

  def wander(wanderlust: Wanderlust): Collisions = {
    val startingPoint = wanderlust.keepId.map(VertexId(_)) orElse wanderlust.uriId.map(VertexId(_)) getOrElse VertexId(wanderlust.userId)
    val preferredCollisions = getPreferredCollisions(wanderlust)
    val rawCollisions = doWander(startingPoint, preferredCollisions, wanderlust.steps, wanderlust.restartProbability)
    getCollisions(rawCollisions)
  }

  private def doWander(startingPoint: VertexId, preferredCollisions: Set[VertexType], steps: Int, restartProbability: Double): Map[VertexId, Int] = {
    val journal = new DestinationJournal()
    graph.readOnly { reader =>
      val wanderer = reader.getNewVertexReader()
      val scout = reader.getNewVertexReader()
      val scoutingWanderer = new ScoutingWanderer(wanderer, scout)
      val teleporter = new UniformTeleporter(Set(startingPoint), preferredCollisions, restartProbability)
      val resolver = new RestrictedDestinationResolver(VertexKind.all)
      scoutingWanderer.wander(steps, teleporter, resolver, journal)
    }

    journal.getDestinations()
  }

  private def getCollisions(rawCollisions: Map[VertexId, Int]): Collisions = {
    val users = mutable.Map[Id[User], Int]()
    val uris =  mutable.Map[Id[NormalizedURI], Int]()
    val extra = mutable.Map[String, Int]()
    rawCollisions.foreach {
      case (vertexId, count) if vertexId.kind == UserReader => users += VertexDataId.toUserId(vertexId.asId[UserReader]) -> count
      case (vertexId, count) if vertexId.kind == UriReader => uris += VertexDataId.toNormalizedUriId(vertexId.asId[UriReader]) -> count
      case (vertexId, count) => extra += vertexId.toString() -> count
    }
    Collisions(users.toMap, uris.toMap, extra.toMap)
  }

  private def getPreferredCollisions(wanderlust: Wanderlust): Set[VertexType] = {
    val preferredCollisions = mutable.Set[VertexType]()
    if (wanderlust.encourageUserCollisions) preferredCollisions += UserReader
    if (wanderlust.encourageUriCollisions) preferredCollisions += UriReader
    preferredCollisions.toSet
  }
}
