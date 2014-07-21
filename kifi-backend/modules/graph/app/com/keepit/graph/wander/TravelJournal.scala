package com.keepit.graph.wander

import com.keepit.graph.model.{ VertexId, VertexReader }
import scala.collection.mutable
import com.keepit.graph.model.EdgeKind.EdgeType

trait TravelJournal {
  def onStart(start: VertexReader): Unit
  def onEdgeTraversal(source: VertexReader, destination: VertexReader, edgeKind: EdgeType): Unit
  def onTeleportation(source: VertexReader, destination: VertexReader): Unit
  def onDeadend(deadend: VertexReader, restart: VertexReader): Unit
  def onComplete(end: VertexReader): Unit
}

class TeleportationJournal extends TravelJournal {
  private val randomTeleportations = mutable.Map[VertexId, Int]().withDefaultValue(0)
  private val visited = mutable.Map[VertexId, Int]().withDefaultValue(0)
  private var lastVisited: Option[VertexId] = None
  private var startingVertexId: Option[VertexId] = None

  def onStart(start: VertexReader): Unit = { startingVertexId = Some(start.id) }

  def onEdgeTraversal(source: VertexReader, destination: VertexReader, edgeKind: EdgeType) = {
    lastVisited = Some(source.id)
    visited(source.id) = visited(source.id) + 1
  }

  def onTeleportation(source: VertexReader, destination: VertexReader) = {
    randomTeleportations(source.id) = randomTeleportations(source.id) + 1
    lastVisited = Some(source.id)
    visited(source.id) = visited(source.id) + 1
  }

  def onDeadend(deadend: VertexReader, restart: VertexReader): Unit = {
    lastVisited = Some(deadend.id)
    visited(deadend.id) = visited(deadend.id) + 1
  }

  def onComplete(end: VertexReader): Unit = {
    visited(end.id) = visited(end.id) + 1
  }

  def getTeleportations(): Map[VertexId, Int] = randomTeleportations.toMap
  def getLastVisited(): Option[VertexId] = lastVisited
  def getVisited(): Map[VertexId, Int] = visited.toMap
  def getStartingVertex(): VertexId = startingVertexId getOrElse { throw new IllegalStateException("This journal has not been completed yet.") }
}
