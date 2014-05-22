package com.keepit.graph.wander

import com.keepit.graph.model.{VertexId, EdgeDataReader, EdgeKind, VertexReader}
import scala.collection.mutable

trait TravelJournal {
  def onTeleportation(source: VertexReader, destination: VertexReader): Unit
  def onEdgeTraversal(source: VertexReader, destination: VertexReader, edgeKind: EdgeKind[_ <: EdgeDataReader]): Unit
}

class DestinationJournal extends TravelJournal {
  private val reached = mutable.Map[VertexId, Int]().withDefaultValue(0)

  def onTeleportation(source: VertexReader, destination: VertexReader) = {
    reached(source.id) = reached(source.id) + 1
  }

  def onEdgeTraversal(source: VertexReader, destination: VertexReader, edgeKind: EdgeKind[_ <: EdgeDataReader]) = {}

  def getDestinations(): Map[VertexId, Int] = reached.toMap
}
