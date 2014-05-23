package com.keepit.graph.wander

import com.keepit.graph.model._
import scala.collection.mutable
import com.keepit.common.math.ProbabilityDensity
import scala.Some
import com.keepit.graph.model.EdgeKind.EdgeType

trait Wanderer {
  def wander(steps: Int, teleporter: Teleporter, resolver: EdgeWeightResolver, journal: TravelJournal): Unit
}

class ScoutingWanderer(wanderer: GlobalVertexReader, scout: GlobalVertexReader) {

  def wander(steps: Int, teleporter: Teleporter, resolver: EdgeWeightResolver, journal: TravelJournal): Unit = {
    val probabilityCache = mutable.Map[VertexId, ProbabilityDensity[(VertexId, EdgeType)]]()
    wanderer.moveTo(teleporter.surely)
    var step = 0
    while (step < steps) {
      teleporter.maybe(wanderer) match {
        case Some(newStart) => teleportTo(newStart, journal)
        case None => {
          assessOutgoingEdges(resolver, probabilityCache).sample(Math.random()) match {
            case Some((nextDestination, edgeKind)) => traverseTo(nextDestination, edgeKind, journal)
            case None => teleportTo(teleporter.surely, journal)
          }
        }
      }
      step += 1
    }
  }

  private def teleportTo(destination: VertexId, journal: TravelJournal): Unit = {
    scout.moveTo(destination)
    journal.onTeleportation(wanderer, scout)
    wanderer.moveTo(destination)
  }

  private def traverseTo(destination: VertexId, edgeKind: EdgeType, journal: TravelJournal): Unit = {
    scout.moveTo(destination)
    journal.onEdgeTraversal(wanderer, scout, edgeKind)
    wanderer.moveTo(destination)
  }

  private def assessOutgoingEdges(resolver: EdgeWeightResolver, cache: mutable.Map[VertexId, ProbabilityDensity[(VertexId, EdgeType)]]): ProbabilityDensity[(VertexId, EdgeType)] = {
    cache getOrElseUpdate (wanderer.id, {
      var weights = mutable.MutableList[((VertexId, EdgeKind[_ <:EdgeDataReader]), Double)]()
      while (weights.length < wanderer.edgeReader.degree) {
        wanderer.edgeReader.moveToNextEdge()
        val destination = wanderer.edgeReader.destination
        scout.moveTo(destination)
        val weight = resolver.weight(wanderer, scout, wanderer.edgeReader)
        weights += ((destination, wanderer.edgeReader.kind) -> weight)
      }
      ProbabilityDensity.normalized(weights)
    })
  }
}
