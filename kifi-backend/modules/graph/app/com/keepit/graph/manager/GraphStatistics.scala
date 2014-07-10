package com.keepit.graph.manager

import com.keepit.graph.model.{ EdgeKind, VertexKind }
import java.util.concurrent.atomic.AtomicLong
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.graph.model.EdgeKind.EdgeType

case class GraphStatistics(vertexCounts: Map[VertexType, Long], edgeCounts: Map[(VertexType, VertexType, EdgeType), Long]) {
  def outgoingDegrees: Map[VertexType, Double] = {
    val edgeCountsBySource = edgeCounts.groupBy { case ((source, _, _), _) => source }
    val outgoingDegrees = edgeCountsBySource.map {
      case (source, outgoingEdgeCounts) =>
        source -> outgoingEdgeCounts.values.sum.toDouble / vertexCounts(source)
    }
    outgoingDegrees.toMap
  }

  def incomingDegrees: Map[VertexType, Double] = {
    val edgeCountsByDestination = edgeCounts.groupBy { case ((_, destination, _), _) => destination }
    val incomingDegrees = edgeCountsByDestination.map {
      case (destination, incomingEdgeCounts) =>
        destination -> incomingEdgeCounts.values.sum.toDouble / vertexCounts(destination)
    }
    incomingDegrees.toMap
  }

  def outgoingDegreesByEdgeType: Map[(VertexType, VertexType, EdgeType), Double] = edgeCounts.map {
    case (edgeType @ (source, _, _), count) =>
      edgeType -> count.toDouble / vertexCounts(source)
  }

  def incomingDegreesByEdgeType: Map[(VertexType, VertexType, EdgeType), Double] = edgeCounts.map {
    case (edgeType @ (_, destination, _), count) =>
      edgeType -> count.toDouble / vertexCounts(destination)
  }
}

object GraphStatistics {
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
      statistics.vertexCounts.map {
        case (vertexKind, count) =>
          vertexKind.code -> (count.toString, outgoingDegrees(vertexKind), incomingDegrees(vertexKind))
      }.toMap,
      statistics.edgeCounts.map {
        case (edgeType @ (sourceKind, destinationKind, edgeKind), count) =>
          (sourceKind.code, destinationKind.code, edgeKind.code) -> (count.toString, outgoingDegreesByEdgeType(edgeType), incomingDegreesByEdgeType(edgeType))
      }.toMap
    )
  }
}
