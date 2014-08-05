package com.keepit.graph.wander

import com.keepit.graph.model._
import scala.collection.mutable
import com.keepit.common.math.ProbabilityDensity
import com.keepit.graph.model.EdgeKind.EdgeType
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.graph.model.Component.Component
import play.api.Logger

trait Wanderer {
  def wander(steps: Int, teleporter: Teleporter, resolver: EdgeResolver, journal: TravelJournal): Unit
}

class ScoutingWanderer(wanderer: GlobalVertexReader, scout: GlobalVertexReader) {
  type TransitionProbabilityCache = mutable.Map[(VertexId, VertexType, EdgeType), ProbabilityDensity[VertexId]]

  lazy val log = Logger("com.keepit.wanderer")

  def wander(steps: Int, teleporter: Teleporter, resolver: EdgeResolver, journal: TravelJournal): Unit = {
    log.info(s"Wandering for $steps steps")
    val probabilityCache: TransitionProbabilityCache = mutable.Map()
    teleportTo(teleporter.surely, journal, isStart = true)
    var step = 0
    while (step < steps) {
      tryAndMove(teleporter, resolver, journal, probabilityCache, 1)
      step += 1
    }
    log.info(s"[Complete] ${wanderer.id}")
    journal.onComplete(wanderer)
  }

  private def tryAndMove(teleporter: Teleporter, resolver: EdgeResolver, journal: TravelJournal, cache: TransitionProbabilityCache, retries: Int): Unit = {
    try { move(teleporter, resolver, journal, cache) }
    catch {
      case VertexNotFoundException(id) if retries > 0 =>
        log.warn(s"Clearing probability cache and retrying (remaining attempts: $retries) after VertexNotFoundException: $id")
        cache.clear()
        tryAndMove(teleporter, resolver, journal, cache, retries - 1)
    }
  }

  private def move(teleporter: Teleporter, resolver: EdgeResolver, journal: TravelJournal, cache: TransitionProbabilityCache): Unit = {
    teleporter.maybe(wanderer) match {
      case Some(newStart) => teleportTo(newStart, journal)
      case None => {
        sampleOutgoingComponent(resolver).flatMap(sampleDestination(_, resolver, cache)) match {
          case Some((nextDestination, edgeKind)) => traverseTo(nextDestination, edgeKind, journal)
          case None => teleportTo(teleporter.surely, journal, isDeadend = true)
        }
      }
    }
  }

  private def teleportTo(destination: VertexId, journal: TravelJournal, isDeadend: Boolean = false, isStart: Boolean = false): Unit = {
    if (isStart) {
      wanderer.moveTo(destination)
      log.info(s"[Start] ${wanderer.id}")
      journal.onStart(wanderer)
    } else {
      scout.moveTo(wanderer.id)
      wanderer.moveTo(destination)
      if (isDeadend) {
        log.info(s"[Deadend] ${scout.id} --> ${wanderer.id}")
        journal.onDeadend(scout, wanderer)
      } else {
        log.info(s"[Teleportation] ${scout.id} --> ${wanderer.id}")
        journal.onTeleportation(scout, wanderer)
      }
    }
  }

  private def traverseTo(destination: VertexId, edgeKind: EdgeType, journal: TravelJournal): Unit = {
    scout.moveTo(wanderer.id)
    wanderer.moveTo(destination)
    log.info(s"[Traverse] ${scout.id} --> ${wanderer.id} | ${edgeKind.code}")
    journal.onEdgeTraversal(scout, wanderer, edgeKind)
  }

  private def sampleOutgoingComponent(resolver: EdgeResolver): Option[Component] = {
    val componentWeights = mutable.MutableList[(Component, Double)]()
    while (wanderer.outgoingEdgeReader.moveToNextComponent()) {
      val component = wanderer.outgoingEdgeReader.component
      val weight = resolver.weightComponent(component)
      componentWeights += component -> weight
    }
    val probability = ProbabilityDensity.normalized(componentWeights)
    probability.sample(Math.random())
  }

  private def sampleDestination(component: Component, resolver: EdgeResolver, cache: TransitionProbabilityCache): Option[(VertexId, EdgeType)] = {
    val (_, destinationKind, edgeKind) = component
    val key = (wanderer.id, destinationKind, edgeKind)
    val probability = cache.getOrElseUpdate(key, computeDestinationProbability(component, resolver))
    probability.sample(Math.random()).map { destination => (destination, edgeKind) }
  }

  private def computeDestinationProbability(component: Component, resolver: EdgeResolver): ProbabilityDensity[VertexId] = {
    val edgeWeights = mutable.MutableList[(VertexId, Double)]()
    wanderer.outgoingEdgeReader.reset()
    while (wanderer.outgoingEdgeReader.moveToNextComponent()) {
      if (wanderer.outgoingEdgeReader.component == component) {
        while (wanderer.outgoingEdgeReader.moveToNextEdge()) {
          val destination = wanderer.outgoingEdgeReader.destination
          scout.moveTo(destination)
          val weight = resolver.weightEdge(wanderer, scout, wanderer.outgoingEdgeReader)
          edgeWeights += (destination -> weight)
        }
      }
    }
    ProbabilityDensity.normalized(edgeWeights)
  }
}
