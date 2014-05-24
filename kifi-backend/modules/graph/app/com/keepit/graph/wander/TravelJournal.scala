package com.keepit.graph.wander

import com.keepit.graph.model.{VertexId, EdgeDataReader, EdgeKind, VertexReader}
import scala.collection.mutable
import com.keepit.graph.model.VertexKind.VertexType

trait TravelJournal {
  def onStart(start: VertexReader): Unit
  def onEdgeTraversal(source: VertexReader, destination: VertexReader, edgeKind: EdgeKind[_ <: EdgeDataReader]): Unit
  def onTeleportation(source: VertexReader, destination: VertexReader): Unit
  def onDeadend(deadend: VertexReader, restart: VertexReader): Unit
  def onComplete(end: VertexReader): Unit
}

class TeleportationJournal extends TravelJournal {
  private val randomTeleportations = mutable.Map[VertexId, Int]().withDefaultValue(0)
  private val visited = mutable.Map[VertexType, Int]().withDefaultValue(0)
  private var lastVisited: Option[VertexId] = None

  def onStart(start: VertexReader): Unit = {}

  def onEdgeTraversal(source: VertexReader, destination: VertexReader, edgeKind: EdgeKind[_ <: EdgeDataReader]) = {
    lastVisited = Some(source.id)
    visited(source.kind) = visited(source.kind) + 1
  }

  def onTeleportation(source: VertexReader, destination: VertexReader) = {
    randomTeleportations(source.id) = randomTeleportations(source.id) + 1
    lastVisited = Some(source.id)
    visited(source.kind) = visited(source.kind) + 1
  }

  def onDeadend(deadend: VertexReader, restart: VertexReader): Unit = {
    lastVisited = Some(deadend.id)
    visited(deadend.kind) = visited(deadend.kind) + 1
  }

  def onComplete(end: VertexReader): Unit = {
    visited(end.kind) = visited(end.kind) + 1
  }

  def getTeleportations(): Map[VertexId, Int] = randomTeleportations.toMap
  def getLastVisited(): Option[VertexId] = lastVisited
  def getVisited(): Map[VertexType, Int] = visited.toMap
}

