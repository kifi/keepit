package com.keepit.graph.wander

import com.keepit.graph.model._
import scala.collection.mutable
import com.keepit.common.math.ProbabilityDensity
import scala.Some
import com.keepit.graph.model.EdgeKind.EdgeType
import com.keepit.graph.model.VertexKind.VertexType

trait Wanderer {
  def wander(steps: Int, teleporter: Teleporter, resolver: EdgeResolver, journal: TravelJournal): Unit
}

class ScoutingWanderer(wanderer: GlobalVertexReader, scout: GlobalVertexReader) {

  def wander(steps: Int, teleporter: Teleporter, resolver: EdgeResolver, journal: TravelJournal): Unit = {
    val probabilityCache = mutable.Map[(VertexId, VertexType, EdgeType), ProbabilityDensity[VertexId]]()
    teleportTo(teleporter.surely, journal, isStart = true)
    var step = 0
    while (step < steps) {
      teleporter.maybe(wanderer) match {
        case Some(newStart) => teleportTo(newStart, journal)
        case None => {
          sampleComponent(resolver).flatMap(sampleDestination(_, resolver, probabilityCache)) match {
            case Some((nextDestination, edgeKind)) => traverseTo(nextDestination, edgeKind, journal)
            case None => teleportTo(teleporter.surely, journal, isDeadend = true)
          }
        }
      }
      step += 1
    }
    journal.onComplete(wanderer)
  }

  private def teleportTo(destination: VertexId, journal: TravelJournal, isDeadend: Boolean = false, isStart: Boolean = false): Unit = {
    scout.moveTo(destination)
    if (isStart) journal.onStart(scout)
    else if (isDeadend) journal.onDeadend(wanderer, scout)
    else journal.onTeleportation(wanderer, scout)
    wanderer.moveTo(destination)
  }

  private def traverseTo(destination: VertexId, edgeKind: EdgeType, journal: TravelJournal): Unit = {
    scout.moveTo(destination)
    journal.onEdgeTraversal(wanderer, scout, edgeKind)
    wanderer.moveTo(destination)
  }

  private def sampleComponent(resolver: EdgeResolver): Option[(VertexType, EdgeType)] = {
    val componentWeights = mutable.MutableList[((VertexType, EdgeType), Double)]()
    while (wanderer.edgeReader.moveToNextComponent()) {
      val (destinationKind, edgeKind) = wanderer.edgeReader.component
      val weight = resolver.weightComponent(wanderer.kind, destinationKind, edgeKind)
      componentWeights += (destinationKind, edgeKind) -> weight
    }
    ProbabilityDensity.normalized(componentWeights).sample(Math.random())
  }

  private def sampleDestination(component: (VertexType, EdgeType), resolver: EdgeResolver, cache: mutable.Map[(VertexId, VertexType, EdgeType), ProbabilityDensity[VertexId]]): Option[(VertexId, EdgeType)] = {
    val (destinationKind, edgeKind) = component
    val key = (wanderer.id, destinationKind, edgeKind)
    val probability = cache getOrElseUpdate (key, computeDestinationProbability(component, resolver))
    probability.sample(Math.random()).map { destination => (destination, edgeKind) }
  }

  private def computeDestinationProbability(component: (VertexType, EdgeType), resolver: EdgeResolver): ProbabilityDensity[VertexId] = {
    val edgeWeights = mutable.MutableList[(VertexId, Double)]()
    while (wanderer.edgeReader.moveToNextComponent()) {
      if (wanderer.edgeReader.component == component) {
        while (wanderer.edgeReader.moveToNextEdge()) {
          val destination = wanderer.edgeReader.destination
          scout.moveTo(destination)
          val weight = resolver.weightEdge(wanderer, scout, wanderer.edgeReader)
          edgeWeights += (destination -> weight)
        }
      }
    }
    ProbabilityDensity.normalized(edgeWeights)
  }
}
