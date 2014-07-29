package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.mutable.{ Map => MutableMap, Set => MutableSet }
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.graph.model.EdgeKind.EdgeType
import play.api.libs.json._

class MutableVertex(var data: VertexDataReader, val outgoingEdges: MutableOutgoingEdges, val incomingEdges: MutableIncomingEdges) extends Vertex {

  def saveOutgoingEdge(destinationId: VertexId, edgeData: EdgeDataReader): Boolean = {
    val component = (data.kind, destinationId.kind, edgeData.kind)
    if (!outgoingEdges.edges.contains(component)) { outgoingEdges.edges += (component -> MutableMap()) }
    val isNewEdge = !outgoingEdges.edges(component).contains(destinationId)
    outgoingEdges.edges(component) += (destinationId -> edgeData)
    isNewEdge
  }

  def removeOutgoingEdge(destinationId: VertexId, edgeKind: EdgeType): Unit = {
    val component = (data.kind, destinationId.kind, edgeKind)
    outgoingEdges.edges(component) -= destinationId
    if (outgoingEdges.edges(component).isEmpty) { outgoingEdges.edges -= component }
  }

  def addIncomingEdge(sourceId: VertexId, edgeKind: EdgeType): Unit = {
    val component = (sourceId.kind, data.kind, edgeKind)
    if (!incomingEdges.edges.contains(component)) { incomingEdges.edges += (component -> MutableSet()) }
    incomingEdges.edges(component) += sourceId
  }

  def removeIncomingEdge(sourceId: VertexId, edgeKind: EdgeType): Unit = {
    val component = (sourceId.kind, data.kind, edgeKind)
    incomingEdges.edges(component) -= sourceId
    if (incomingEdges.edges(component).isEmpty) { incomingEdges.edges -= component }
  }
}

object MutableVertex {
  def apply(data: VertexDataReader): MutableVertex = new MutableVertex(data, MutableOutgoingEdges(), MutableIncomingEdges())

  val lossyFormat = new Format[MutableVertex] {
    def writes(vertex: MutableVertex): JsValue = Json.obj(
      "data" -> VertexDataReader.writes.writes(vertex.data),
      "edges" -> JsArray(vertex.outgoingEdges.edges.flatMap {
        case (component, edges) =>
          edges.flatMap {
            case (destinationId, edgeDataReader) =>
              Seq(VertexId.format.writes(destinationId), EdgeDataReader.writes.writes(edgeDataReader))
          }
      }.toSeq)
    )

    def reads(json: JsValue): JsResult[MutableVertex] = {
      for {
        data <- VertexDataReader.readsAsVertexData.reads(json \ "data").map(_.asReader)
        edgeIterator <- (json \ "edges").validate[JsArray].map { jsArray =>
          jsArray.value.sliding(2, 2).map {
            case Seq(destinationId, edgeData) =>
              destinationId.as[VertexId] -> edgeData.as[EdgeData[_ <: EdgeDataReader]].asReader
          }
        }
      } yield { new MutableVertex(data, MutableOutgoingEdges(data.kind, edgeIterator), MutableIncomingEdges()) }
    }
  }

  // This method recovers the global information lost by the lossyFormat at serialization of each vertex
  def initializeIncomingEdges(vertices: scala.collection.Map[VertexId, MutableVertex]) = {
    vertices.foreach {
      case (sourceId, sourceVertex) =>
        val sourceKind = sourceVertex.data.kind
        sourceVertex.outgoingEdges.edges.valuesIterator.flatten.foreach {
          case (destinationId, edgeData) =>
            val destinationVertex = vertices.getOrElse(destinationId,
              throw new IllegalStateException(s"Could not find destination vertex of edge ${(sourceId, destinationId, edgeData.kind)}")
            )
            val component = (sourceKind, destinationId.kind, edgeData.kind)
            if (!destinationVertex.incomingEdges.edges.contains(component)) { destinationVertex.incomingEdges.edges += (component -> MutableSet()) }
            destinationVertex.incomingEdges.edges(component) += sourceId
        }
    }
  }
}

class MutableOutgoingEdges(val edges: MutableMap[(VertexType, VertexType, EdgeType), MutableMap[VertexId, EdgeDataReader]]) extends OutgoingEdges

object MutableOutgoingEdges {
  def apply(): MutableOutgoingEdges = new MutableOutgoingEdges(MutableMap())

  def apply(sourceVertexKind: VertexType, edgeIterator: Iterator[(VertexId, EdgeDataReader)]): MutableOutgoingEdges = {
    val mutableEdges = MutableMap[(VertexType, VertexType, EdgeType), MutableMap[VertexId, EdgeDataReader]]()
    edgeIterator.foreach {
      case (destinationId, edgeData) =>
        val component = (sourceVertexKind, destinationId.kind, edgeData.kind)
        if (!mutableEdges.contains(component)) { mutableEdges += (component -> MutableMap[VertexId, EdgeDataReader]()) }
        mutableEdges(component) += (destinationId -> edgeData)
    }
    new MutableOutgoingEdges(mutableEdges)
  }

  def apply(outgoingEdges: OutgoingEdges): MutableOutgoingEdges = {
    val mutableEdges = MutableMap[(VertexType, VertexType, EdgeType), MutableMap[VertexId, EdgeDataReader]]()
    outgoingEdges.edges.foreach {
      case (component, destinations) =>
        val mutableDestinations = MutableMap[VertexId, EdgeDataReader]() ++= destinations
        mutableEdges += (component -> mutableDestinations)
    }
    new MutableOutgoingEdges(mutableEdges)
  }
}

class MutableIncomingEdges(val edges: MutableMap[(VertexType, VertexType, EdgeType), MutableSet[VertexId]]) extends IncomingEdges

object MutableIncomingEdges {
  def apply(): MutableIncomingEdges = new MutableIncomingEdges(MutableMap())

  def apply(incomingEdges: IncomingEdges): MutableIncomingEdges = {
    val mutableEdges = MutableMap[(VertexType, VertexType, EdgeType), MutableSet[VertexId]]()
    incomingEdges.edges.foreach {
      case (component, sources) =>
        val mutableSources = MutableSet[VertexId]() ++= sources
        mutableEdges += (component -> mutableSources)
    }
    new MutableIncomingEdges(mutableEdges)
  }
}
