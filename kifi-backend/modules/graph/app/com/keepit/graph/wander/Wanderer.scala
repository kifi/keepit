package com.keepit.graph.wander

import com.keepit.graph.model._
import scala.collection.mutable
import com.keepit.common.math.ProbabilityDensity
import scala.Some

trait Wanderer {
  def wander(steps: Int, teleport: Teleporter, resolve: EdgeWeightResolver, journal: TravelJournal): Unit
}

class ScoutingWanderer(wanderer: GlobalVertexReader, scout: GlobalVertexReader) {
  def wander(steps: Int, teleporter: Teleporter, resolver: EdgeWeightResolver, journal: TravelJournal): Unit = {

    def teleportTo(destination: VertexId): Unit = {
      scout.moveTo(destination)
      journal.onTeleportation(wanderer, scout)
      wanderer.moveTo(destination)
    }

    def traverseTo(destination: VertexId, edgeKind: EdgeKind[_ <: EdgeDataReader]): Unit = {
      scout.moveTo(destination)
      journal.onEdgeTraversal(wanderer, scout, edgeKind)
      wanderer.moveTo(destination)
    }

    wanderer.moveTo(teleporter.surely)
    var step = 0
    while (step < steps) {
      teleporter.maybe(wanderer) match {
        case Some(newStart) => teleportTo(newStart)
        case None => {
          var weights = mutable.MutableList[((VertexId, EdgeKind[_ <:EdgeDataReader]), Double)]()

          while (weights.length < wanderer.edgeReader.degree) {
            wanderer.edgeReader.moveToNextEdge()
            val destination = wanderer.edgeReader.destination
            scout.moveTo(destination)
            val weight = resolver.weight(wanderer, scout, wanderer.edgeReader)
            weights += ((destination, wanderer.edgeReader.kind) -> weight)
          }

          val destinationProbability = ProbabilityDensity.normalized(weights)
          destinationProbability.sample(Math.random()) match {
            case Some((nextDestination, edgeKind)) => traverseTo(nextDestination, edgeKind)
            case None => {  // failed to move, force teleportation
              teleportTo(teleporter.surely)
            }
          }
        }
      }
      step += 1
    }
  }

}
