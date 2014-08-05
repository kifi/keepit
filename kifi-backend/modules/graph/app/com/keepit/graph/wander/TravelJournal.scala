package com.keepit.graph.wander

import com.keepit.graph.model.{ VertexId, VertexReader }
import scala.collection.mutable
import com.keepit.graph.model.EdgeKind.EdgeType
import com.keepit.common.logging.Logging
import org.joda.time.DateTime
import com.keepit.common.time._

trait TravelJournal {
  def onStart(start: VertexReader): Unit
  def onEdgeTraversal(source: VertexReader, destination: VertexReader, edgeKind: EdgeType): Unit
  def onTeleportation(source: VertexReader, destination: VertexReader): Unit
  def onDeadend(deadend: VertexReader, restart: VertexReader): Unit
  def onComplete(end: VertexReader): Unit
}

class TeleportationJournal(clock: Clock) extends TravelJournal with Logging {
  private val randomTeleportations = mutable.Map[VertexId, Int]().withDefaultValue(0)
  private val visited = mutable.Map[VertexId, Int]().withDefaultValue(0)
  private var lastVisited: Option[VertexId] = None
  private var startingVertexId: Option[VertexId] = None
  private var startTime: Option[DateTime] = None
  private var steps = 0

  def onStart(start: VertexReader): Unit = {
    startingVertexId = Some(start.id)
    startTime = Some(clock.now())
  }

  def onEdgeTraversal(source: VertexReader, destination: VertexReader, edgeKind: EdgeType) = {
    lastVisited = Some(source.id)
    visited(source.id) = visited(source.id) + 1
    steps += 1
  }

  def onTeleportation(source: VertexReader, destination: VertexReader) = {
    randomTeleportations(source.id) = randomTeleportations(source.id) + 1
    lastVisited = Some(source.id)
    visited(source.id) = visited(source.id) + 1
    steps += 1
  }

  def onDeadend(deadend: VertexReader, restart: VertexReader): Unit = {
    lastVisited = Some(deadend.id)
    visited(deadend.id) = visited(deadend.id) + 1
    steps += 1
  }

  def onComplete(end: VertexReader): Unit = {
    val endTime = clock.now()
    log.info(s"Wandered for ${steps} steps during ${endTime.getMillis - startTime.get.getMillis} ms.")
  }

  def getTeleportations(): Map[VertexId, Int] = randomTeleportations.toMap
  def getLastVisited(): Option[VertexId] = lastVisited
  def getVisited(): Map[VertexId, Int] = visited.toMap
  def getStartingVertex(): VertexId = startingVertexId getOrElse { throw new IllegalStateException("This journal has not been completed yet.") }
  def getCompletedSteps(): Int = steps
}
