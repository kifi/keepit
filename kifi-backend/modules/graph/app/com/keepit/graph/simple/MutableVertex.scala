package com.keepit.graph.simple

import java.io.{ ObjectInputStream, ObjectOutputStream }

import com.keepit.graph.model._
import scala.collection.mutable.{ Map => MutableMap, Set => MutableSet }
import com.keepit.graph.model.EdgeKind.EdgeType
import com.keepit.graph.model.Component.Component
import play.api.libs.json._

class MutableVertex(var data: VertexDataReader, val outgoingEdges: MutableOutgoingEdges, val incomingEdges: MutableIncomingEdges) extends Vertex {

  def saveOutgoingEdge(destinationId: VertexId, edgeData: EdgeDataReader): Boolean = {
    val component = Component(data.kind, destinationId.kind, edgeData.kind)
    if (!outgoingEdges.edges.contains(component)) { outgoingEdges.edges += (component -> MutableMap()) }
    val isNewEdge = !outgoingEdges.edges(component).contains(destinationId)
    outgoingEdges.edges(component) += (destinationId -> edgeData)
    isNewEdge
  }

  def removeOutgoingEdge(destinationId: VertexId, edgeKind: EdgeType): Unit = {
    val component = Component(data.kind, destinationId.kind, edgeKind)
    outgoingEdges.edges(component) -= destinationId
    if (outgoingEdges.edges(component).isEmpty) { outgoingEdges.edges -= component }
  }

  def addIncomingEdge(sourceId: VertexId, edgeKind: EdgeType): Unit = {
    val component = Component(sourceId.kind, data.kind, edgeKind)
    if (!incomingEdges.edges.contains(component)) { incomingEdges.edges += (component -> MutableSet()) }
    incomingEdges.edges(component) += sourceId
  }

  def removeIncomingEdge(sourceId: VertexId, edgeKind: EdgeType): Unit = {
    val component = Component(sourceId.kind, data.kind, edgeKind)
    incomingEdges.edges(component) -= sourceId
    if (incomingEdges.edges(component).isEmpty) { incomingEdges.edges -= component }
  }

  /**
   * UTF, Int, (Long, UTF)
   */
  def write(out: ObjectOutputStream): Unit = {
    out.writeUTF(VertexDataReader.writes.writes(data).toString)
    out.writeInt(outgoingEdges.edges.foldLeft(0)((count, edges) => count + edges._2.size))
    outgoingEdges.edges foreach {
      case (component, edges) =>
        edges foreach {
          case (destinationId, edgeDataReader) =>
            out.writeLong(destinationId.id)
            out.writeUTF(EdgeDataReader.writes.writes(edgeDataReader).toString())
        }
    }
  }
}

object MutableVertex {
  def apply(data: VertexDataReader): MutableVertex = new MutableVertex(data, MutableOutgoingEdges(), MutableIncomingEdges())

  def reads(in: ObjectInputStream): MutableVertex = {
    val dataJson = in.readUTF()
    VertexDataReader.readsAsVertexData.reads(Json.parse(dataJson)) match {
      case JsError(error) => throw new Exception(s"error parsing $dataJson: $error")
      case JsSuccess(data, _) =>
        val newVertex = MutableVertex(data.asReader)
        val outgoingEdgesSize = in.readInt()
        for (i <- 0 until outgoingEdgesSize) {
          val destinationId = VertexId(in.readLong())
          val edgeData = EdgeDataReader.readsAsEdgeData.reads(Json.parse(in.readUTF())).get.asReader
          newVertex.saveOutgoingEdge(destinationId, edgeData)
        }
        newVertex
    }
  }

  // This "lossy" formatter ignores the vertex's incoming edges, which have to be recovered globally (see initializeIncomingEdges method)
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
      } yield {
        val newVertex = MutableVertex(data)
        edgeIterator.foreach { case (destinationId, edgeData) => newVertex.saveOutgoingEdge(destinationId, edgeData) }
        newVertex
      }
    }
  }

  // This method recovers the global information lost by the lossyFormat at serialization of each vertex
  def initializeIncomingEdges(vertices: scala.collection.Map[VertexId, MutableVertex]) = {
    vertices.foreach {
      case (sourceId, sourceVertex) =>
        sourceVertex.outgoingEdges.edges.valuesIterator.flatten.foreach {
          case (destinationId, edgeData) =>
            val destinationVertex = vertices.getOrElse(destinationId,
              throw new IllegalStateException(s"Could not find destination vertex of edge ${(sourceId, destinationId, edgeData.kind)}")
            )
            destinationVertex.addIncomingEdge(sourceId, edgeData.kind)
        }
    }
  }
}

class MutableOutgoingEdges(val edges: MutableMap[Component, MutableMap[VertexId, EdgeDataReader]]) extends OutgoingEdges

object MutableOutgoingEdges {
  def apply(): MutableOutgoingEdges = new MutableOutgoingEdges(MutableMap())

  def apply(outgoingEdges: OutgoingEdges): MutableOutgoingEdges = {
    val mutableEdges = MutableMap[Component, MutableMap[VertexId, EdgeDataReader]]()
    outgoingEdges.edges.foreach {
      case (component, destinations) =>
        val mutableDestinations = MutableMap[VertexId, EdgeDataReader]() ++= destinations
        mutableEdges += (component -> mutableDestinations)
    }
    new MutableOutgoingEdges(mutableEdges)
  }
}

class MutableIncomingEdges(val edges: MutableMap[Component, MutableSet[VertexId]]) extends IncomingEdges

object MutableIncomingEdges {
  def apply(): MutableIncomingEdges = new MutableIncomingEdges(MutableMap())

  def apply(incomingEdges: IncomingEdges): MutableIncomingEdges = {
    val mutableEdges = MutableMap[Component, MutableSet[VertexId]]()
    incomingEdges.edges.foreach {
      case (component, sources) =>
        val mutableSources = MutableSet[VertexId]() ++= sources
        mutableEdges += (component -> mutableSources)
    }
    new MutableIncomingEdges(mutableEdges)
  }
}
