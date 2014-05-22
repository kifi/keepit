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
    val startingVertexKind = VertexKind.apply(wanderlust.startingVertexKind)
    val startingVertexId = VertexId(startingVertexKind)(wanderlust.startingVertexDataId)
    val preferredCollisions = wanderlust.preferredCollisions.map(VertexKind(_))
    val rawCollisions = doWander(startingVertexId, preferredCollisions, wanderlust.steps, wanderlust.restartProbability)
    getCollisions(rawCollisions)
  }

  private def doWander(startingVertexId: VertexId, preferredCollisions: Set[VertexType], steps: Int, restartProbability: Double): Map[VertexId, Int] = {
    val journal = new DestinationJournal()
    graph.readOnly { reader =>
      val wanderer = reader.getNewVertexReader()
      val scout = reader.getNewVertexReader()
      val scoutingWanderer = new ScoutingWanderer(wanderer, scout)
      val teleporter = new UniformTeleporter(Set(startingVertexId), preferredCollisions, restartProbability)
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
}
