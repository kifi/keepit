package com.keepit.graph.wander

import com.keepit.graph.model._
import scala.collection.mutable
import com.keepit.common.math.ProbabilityDensity
import scala.Some
import com.keepit.graph.model.EdgeKind.EdgeType
import com.keepit.graph.model.VertexKind.VertexType
import play.api.Logger

trait Wanderer {
  def wander(steps: Int, teleporter: Teleporter, resolver: EdgeResolver, journal: TravelJournal): Unit
}

class ScoutingWanderer(wanderer: GlobalVertexReader, scout: GlobalVertexReader) {

  lazy val log = Logger("com.keepit.wanderer")

  def wander(steps: Int, teleporter: Teleporter, resolver: EdgeResolver, journal: TravelJournal): Unit = {
    log.info(s"Wandering for $steps steps")
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
    log.info(s"[Complete] ${wanderer.id}")
    journal.onComplete(wanderer)
  }

  private def teleportTo(destination: VertexId, journal: TravelJournal, isDeadend: Boolean = false, isStart: Boolean = false): Unit = {
    scout.moveTo(destination)
    if (isStart) {
      log.info(s"[Start] $destination")
      journal.onStart(scout)
    } else if (isDeadend) {
      log.info(s"[Deadend] ${wanderer.id} --> ${scout.id}")
      journal.onDeadend(wanderer, scout)
    } else {
      log.info(s"[Teleportation] ${wanderer.id} --> ${scout.id}")
      journal.onTeleportation(wanderer, scout)
    }
    wanderer.moveTo(destination)
  }

  private def traverseTo(destination: VertexId, edgeKind: EdgeType, journal: TravelJournal): Unit = {
    scout.moveTo(destination)
    log.info(s"[Traverse] ${wanderer.id} --> ${scout.id} | ${edgeKind.code}")
    journal.onEdgeTraversal(wanderer, scout, edgeKind)
    wanderer.moveTo(destination)
  }

  private def sampleComponent(resolver: EdgeResolver): Option[(VertexType, EdgeType)] = {
    val componentWeights = mutable.MutableList[((VertexType, EdgeType), Double)]()
    while (wanderer.edgeReader.moveToNextComponent()) {
      val (destinationKind, edgeKind) = wanderer.edgeReader.component
      val weight = resolver.weightComponent(wanderer, destinationKind, edgeKind)
      componentWeights += (destinationKind, edgeKind) -> weight
    }
    val probability = ProbabilityDensity.normalized(componentWeights)
    probability.sample(Math.random())
  }

  private def sampleDestination(component: (VertexType, EdgeType), resolver: EdgeResolver, cache: mutable.Map[(VertexId, VertexType, EdgeType), ProbabilityDensity[VertexId]]): Option[(VertexId, EdgeType)] = {
    val (destinationKind, edgeKind) = component
    val key = (wanderer.id, destinationKind, edgeKind)
    val probability = cache.getOrElseUpdate(key, computeDestinationProbability(component, resolver))
    probability.sample(Math.random()).map { destination => (destination, edgeKind) }
  }

  private def computeDestinationProbability(component: (VertexType, EdgeType), resolver: EdgeResolver): ProbabilityDensity[VertexId] = {
    val edgeWeights = mutable.MutableList[(VertexId, Double)]()
    wanderer.edgeReader.reset()
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
