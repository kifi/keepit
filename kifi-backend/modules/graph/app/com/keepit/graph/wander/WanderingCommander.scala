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

  private def doWander(startingVertexId: VertexId, preferredCollisions: Set[VertexType], steps: Int, restartProbability: Double): TeleportationJournal = {
    val journal = new TeleportationJournal()
    val teleporter = UniformTeleporter(Set(startingVertexId), restartProbability) { wanderer =>
      wanderer.id != startingVertexId && (preferredCollisions.isEmpty || preferredCollisions.contains(wanderer.kind))
    }
    val resolver = RestrictedDestinationResolver { case (source, destination, edge) =>
      !journal.getLastVisited().exists(_ == destination.id)
    }

    graph.readOnly { reader =>
      val wanderer = reader.getNewVertexReader()
      val scout = reader.getNewVertexReader()
      val scoutingWanderer = new ScoutingWanderer(wanderer, scout)
      scoutingWanderer.wander(steps, teleporter, resolver, journal)
    }
    journal
  }

  private def getCollisions(journal: TeleportationJournal): Collisions = {
    val users = mutable.Map[Id[User], Int]()
    val uris =  mutable.Map[Id[NormalizedURI], Int]()
    val extra = mutable.Map[String, Int]()
    journal.getTeleportations().foreach {
      case (vertexId, count) if vertexId.kind == UserReader => users += VertexDataId.toUserId(vertexId.asId[UserReader]) -> count
      case (vertexId, count) if vertexId.kind == UriReader => uris += VertexDataId.toNormalizedUriId(vertexId.asId[UriReader]) -> count
      case (vertexId, count) => extra += vertexId.toString() -> count
    }
    Collisions(users.toMap, uris.toMap, extra.toMap)
  }
}
