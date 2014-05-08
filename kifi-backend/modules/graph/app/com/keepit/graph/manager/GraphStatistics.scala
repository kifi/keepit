package com.keepit.graph.manager

import com.keepit.graph.model.{EdgeDataReader, EdgeKind, VertexDataReader, VertexKind}
import com.keepit.graph.manager.GraphStatistics.{EdgeType, VertexType}
import java.util.concurrent.atomic.AtomicLong

case class GraphStatistics(vertexStatistics: Map[VertexType, Long], edgeStatistics: Map[(VertexType, VertexType, EdgeType), Long])

object GraphStatistics {
  type VertexType = VertexKind[_ <: VertexDataReader]
  type EdgeType = EdgeKind[_ <: EdgeDataReader]

  private val allEdgeKinds: Set[(VertexType, VertexType, EdgeType)] = for {
    sourceKind <- VertexKind.all
    destinationKind <- VertexKind.all
    edgeKind <- EdgeKind.all
  } yield (sourceKind, destinationKind, edgeKind)

  def newVertexCounter(): Map[VertexType, AtomicLong] = VertexKind.all.map(_ -> new AtomicLong(0)).toMap
  def newEdgeCounter(): Map[(VertexType, VertexType, EdgeType), AtomicLong] = allEdgeKinds.map(_ -> new AtomicLong(0)).toMap

  def filter(vertexCounter: Map[VertexType, AtomicLong], edgeCounter: Map[(VertexType, VertexType, EdgeType), AtomicLong]): GraphStatistics = {
    GraphStatistics(vertexCounter.mapValues(_.get()).filter(_._2 > 0), edgeCounter.mapValues(_.get()).filter(_._2 > 0))
  }

  def prettify(statistics: GraphStatistics): PrettyGraphStatistics = PrettyGraphStatistics(
    statistics.vertexStatistics.map { case (vertexKind, count) => (vertexKind.toString -> count) }.toMap,
    statistics.edgeStatistics.map { case ((sourceKind, destinationKind, edgeKind), count) =>
      (sourceKind.toString, destinationKind.toString, edgeKind.toString) -> count
    }.toMap
  )
}
