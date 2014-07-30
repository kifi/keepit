package com.keepit.graph.manager

import com.keepit.graph.model.{ Component, VertexKind }
import java.util.concurrent.atomic.AtomicLong
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.graph.model.Component.Component

case class GraphStatistics(vertexCounts: Map[VertexType, Long], edgeCounts: Map[Component, Long]) {
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

  def outgoingDegreesByComponent: Map[Component, Double] = edgeCounts.map {
    case (edgeType @ (source, _, _), count) =>
      edgeType -> count.toDouble / vertexCounts(source)
  }

  def incomingDegreesByComponent: Map[Component, Double] = edgeCounts.map {
    case (edgeType @ (_, destination, _), count) =>
      edgeType -> count.toDouble / vertexCounts(destination)
  }
}

object GraphStatistics {
  def newVertexCounter(): Map[VertexType, AtomicLong] = VertexKind.all.map(_ -> new AtomicLong(0)).toMap
  def newEdgeCounter(): Map[Component, AtomicLong] = Component.all.map(_ -> new AtomicLong(0)).toMap

  def filter(vertexCounter: Map[VertexType, AtomicLong], edgeCounter: Map[Component, AtomicLong]): GraphStatistics = {
    GraphStatistics(vertexCounter.mapValues(_.get()).filter(_._2 > 0), edgeCounter.mapValues(_.get()).filter(_._2 > 0))
  }

  def prettify(statistics: GraphStatistics): PrettyGraphStatistics = {
    val outgoingDegrees = statistics.outgoingDegrees.mapValues(deg => f"$deg%.2f").withDefaultValue("")
    val incomingDegrees = statistics.incomingDegrees.mapValues(deg => f"$deg%.2f").withDefaultValue("")
    val outgoingDegreesByComponent = statistics.outgoingDegreesByComponent.mapValues(deg => f"$deg%.2f").withDefaultValue("")
    val incomingDegreesByComponent = statistics.incomingDegreesByComponent.mapValues(deg => f"$deg%.2f").withDefaultValue("")

    PrettyGraphStatistics(
      statistics.vertexCounts.map {
        case (vertexKind, count) =>
          vertexKind.code -> (count.toString, outgoingDegrees(vertexKind), incomingDegrees(vertexKind))
      }.toMap,
      statistics.edgeCounts.map {
        case (component @ (sourceKind, destinationKind, edgeKind), count) =>
          (sourceKind.code, destinationKind.code, edgeKind.code) -> (count.toString, outgoingDegreesByComponent(component), incomingDegreesByComponent(component))
      }.toMap
    )
  }
}
