package com.keepit.graph.wander

import com.keepit.graph.model.{VertexId, GlobalVertexReader}
import scala.collection.mutable
import com.keepit.common.math.ProbabilityDensity

trait Wanderer {
  def wander(steps: Int, teleport: Teleporter, resolve: EdgeWeightResolver): Map[VertexId, Int]
}

class ScoutingWanderer(wanderer: GlobalVertexReader, scout: GlobalVertexReader) {
  def wander(steps: Int, teleport: Teleporter, resolve: EdgeWeightResolver): Map[VertexId, Int] = {
    wanderer.moveTo(teleport.surely)
    val reached = mutable.Map[VertexId, Int]().withDefaultValue(0)
    var step = 0
    while (step < steps) {
      teleport.maybe(wanderer) match {
        case Some(newStart) =>
          reached(wanderer.id) = reached(wanderer.id) + 1
          wanderer.moveTo(newStart)
        case None => {
          var weights = mutable.MutableList[(VertexId, Double)]()

          while (weights.length < wanderer.edgeReader.degree) {
            wanderer.edgeReader.moveToNextEdge()
            val destination = wanderer.edgeReader.destination
            scout.moveTo(destination)
            val weight = resolve.weight(wanderer, scout, wanderer.edgeReader)
            weights += (destination -> weight)
          }

          val destinationProbability = ProbabilityDensity.normalized(weights)
          destinationProbability.sample(Math.random()) match {
            case Some(nextDestination) => wanderer.moveTo(nextDestination)
            case None => {  // failed to move, force teleportation
              reached(wanderer.id) = reached(wanderer.id) + 1
              wanderer.moveTo(teleport.surely)
            }
          }
        }
      }
      step += 1
    }
    reached.toMap
  }
}
