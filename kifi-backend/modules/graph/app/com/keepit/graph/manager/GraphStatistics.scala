package com.keepit.graph.manager

import com.keepit.graph.model.{EdgeDataReader, EdgeKind, VertexDataReader, VertexKind}
import com.keepit.graph.manager.GraphStatistics.{EdgeType, VertexType}
import java.util.concurrent.atomic.AtomicLong

case class GraphStatistics(vertexCounts: Map[VertexType, Long], edgeCounts: Map[(VertexType, VertexType, EdgeType), Long]) {
  def outgoingDegrees: Map[VertexType, Double] = {
    val edgeCountsBySource = edgeCounts.groupBy { case ((source, _, _), _) => source }
    val outgoingDegrees = edgeCountsBySource.map { case (source, outgoingEdgeCounts) =>
      source -> outgoingEdgeCounts.values.sum.toDouble / vertexCounts(source)
    }
    outgoingDegrees.toMap
  }

  def incomingDegrees: Map[VertexType, Double] = {
    val edgeCountsByDestination = edgeCounts.groupBy { case ((_, destination, _), _) => destination }
    val incomingDegrees = edgeCountsByDestination.map { case (destination, incomingEdgeCounts) =>
      destination -> incomingEdgeCounts.values.sum.toDouble / vertexCounts(destination)
    }
    incomingDegrees.toMap
  }

  def outgoingDegreesByEdgeType: Map[(VertexType, VertexType, EdgeType), Double] = edgeCounts.map { case (edgeType @ (source, _, _), count) =>
    edgeType -> count.toDouble / vertexCounts(source)
  }

  def incomingDegreesByEdgeType: Map[(VertexType, VertexType, EdgeType), Double] = edgeCounts.map { case (edgeType @ (_, destination, _), count) =>
    edgeType -> count.toDouble / vertexCounts(destination)
  }
}

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

  def prettify(statistics: GraphStatistics): PrettyGraphStatistics = {
    val outgoingDegrees = statistics.outgoingDegrees.mapValues(deg => f"$deg%.2f").withDefaultValue("")
    val incomingDegrees = statistics.incomingDegrees.mapValues(deg => f"$deg%.2f").withDefaultValue("")
    val outgoingDegreesByEdgeType = statistics.outgoingDegreesByEdgeType.mapValues(deg => f"$deg%.2f").withDefaultValue("")
    val incomingDegreesByEdgeType = statistics.incomingDegreesByEdgeType.mapValues(deg => f"$deg%.2f").withDefaultValue("")

    PrettyGraphStatistics(
      statistics.vertexCounts.map { case (vertexKind, count) =>
        vertexKind.toString.stripSuffix("Reader") -> (count.toString, outgoingDegrees(vertexKind), incomingDegrees(vertexKind))
      }.toMap,
      statistics.edgeCounts.map { case (edgeType @ (sourceKind, destinationKind, edgeKind), count) =>
        (sourceKind.toString.stripSuffix("Reader"), destinationKind.toString.stripSuffix("Reader"), edgeKind.toString.stripSuffix("Reader")) -> (count.toString, outgoingDegreesByEdgeType(edgeType), incomingDegreesByEdgeType(edgeType))
      }.toMap
    )
  }
}
