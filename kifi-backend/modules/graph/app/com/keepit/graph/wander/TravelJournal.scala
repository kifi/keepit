package com.keepit.graph.wander

import com.keepit.graph.model.{VertexId, EdgeDataReader, EdgeKind, VertexReader}
import scala.collection.mutable

trait TravelJournal {
  def onTeleportation(source: VertexReader, destination: VertexReader): Unit
  def onEdgeTraversal(source: VertexReader, destination: VertexReader, edgeKind: EdgeKind[_ <: EdgeDataReader]): Unit
}

class TeleportationJournal extends TravelJournal {
  private val teleportations = mutable.Map[VertexId, Int]().withDefaultValue(0)
  private var lastVisited: Option[VertexId] = None

  def onTeleportation(source: VertexReader, destination: VertexReader) = {
    teleportations(source.id) = teleportations(source.id) + 1
    lastVisited = Some(source.id)
  }

  def onEdgeTraversal(source: VertexReader, destination: VertexReader, edgeKind: EdgeKind[_ <: EdgeDataReader]) = {
    lastVisited = Some(source.id)
  }

  def getTeleportations(): Map[VertexId, Int] = teleportations.toMap
  def getLastVisited(): Option[VertexId] = lastVisited
}

