package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.mutable.{ Map => MutableMap, Set => MutableSet }
import play.api.libs.json._
import java.util.concurrent.atomic.AtomicLong
import com.keepit.graph.manager.GraphStatistics
import com.keepit.common.logging.Logging
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.graph.model.EdgeKind.EdgeType

class MutableVertex(var data: VertexDataReader, val edges: MutableMap[(VertexType, VertexType, EdgeType), MutableMap[VertexId, EdgeDataReader]]) extends Vertex

object MutableVertex {

  def buildEdgeIndex(sourceVertexKind: VertexType, edgeIterator: Iterator[(VertexId, EdgeDataReader)]): MutableMap[(VertexType, VertexType, EdgeType), MutableMap[VertexId, EdgeDataReader]] = {
    val edges = MutableMap[(VertexType, VertexType, EdgeType), MutableMap[VertexId, EdgeDataReader]]()
    edgeIterator.foreach {
      case (destinationId, edgeData) =>
        val component = (sourceVertexKind, destinationId.kind, edgeData.kind)
        if (!edges.contains(component)) { edges += (component -> MutableMap[VertexId, EdgeDataReader]()) }
        edges(component) += (destinationId -> edgeData)
    }
    edges
  }

  implicit val format: Format[MutableVertex] = new Format[MutableVertex] {

    def writes(vertex: MutableVertex): JsValue = Json.obj(
      "data" -> VertexDataReader.writes.writes(vertex.data),
      "edges" -> JsArray(vertex.edges.flatMap {
        case (component, edges) =>
          edges.flatMap {
            case (destinationId, edgeDataReader) =>
              Seq(VertexId.format.writes(destinationId), EdgeDataReader.writes.writes(edgeDataReader))
          }
      }.toSeq
      )
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
      } yield { new MutableVertex(data, buildEdgeIndex(data.kind, edgeIterator)) }
    }
  }
}

class SimpleGraphWriter(
    bufferedVertices: BufferedMap[VertexId, MutableVertex],
    bufferedIncomingEdges: BufferedMap[VertexId, MutableMap[(VertexType, VertexType, EdgeType), MutableSet[VertexId]]],
    vertexStatistics: Map[VertexType, AtomicLong],
    edgeStatistics: Map[(VertexType, VertexType, EdgeType), AtomicLong]) extends SimpleGraphReader(bufferedVertices) with GraphWriter with Logging {

  private val vertexDeltas = GraphStatistics.newVertexCounter()
  private val edgeDeltas: Map[(VertexType, VertexType, EdgeType), AtomicLong] = GraphStatistics.newEdgeCounter()

  private def getBufferedVertex(vertexId: VertexId): Option[MutableVertex] = bufferedVertices.get(vertexId) map {
    case alreadyBufferedVertex if bufferedVertices.hasBuffered(vertexId) => alreadyBufferedVertex
    case vertex => {
      val edges = MutableVertex.buildEdgeIndex(vertex.data.kind, vertex.edges.valuesIterator.flatten)
      val buffered = new MutableVertex(vertex.data, edges)
      bufferedVertices += (vertexId -> buffered)
      buffered
    }
  }

  private def getBufferedIncomingEdges(vertexId: VertexId): MutableMap[(VertexType, VertexType, EdgeType), MutableSet[VertexId]] = bufferedIncomingEdges.get(vertexId) match {
    case Some(alreadyBufferedIncomingEdges) if bufferedIncomingEdges.hasBuffered(vertexId) => alreadyBufferedIncomingEdges
    case incomingEdgesOption => {
      val buffered = MutableMap[(VertexType, VertexType, EdgeType), MutableSet[VertexId]]()
      incomingEdgesOption.foreach { incomingEdges =>
        incomingEdges.foreach {
          case (component, sourceIds) =>
            buffered += component -> (MutableSet() ++= sourceIds)
        }
      }
      bufferedIncomingEdges += (vertexId -> buffered)
      buffered
    }
  }

  def saveVertex[V <: VertexDataReader](data: V): Boolean = {
    val vertexId = VertexId(data.id)(data.kind)
    getBufferedVertex(vertexId) match {
      case Some(bufferedVertex) =>
        bufferedVertex.data = data
        false
      case None =>
        bufferedVertices += (vertexId -> new MutableVertex(data, MutableMap()))
        vertexDeltas(data.kind).incrementAndGet()
        true
    }
  }

  def removeVertex[V <: VertexDataReader](vertex: VertexDataId[V])(implicit vertexKind: VertexKind[V]): Unit = {
    val vertexId = VertexId(vertex)
    Vertex.checkIfVertexExists(bufferedVertices)(vertexId)
    val bufferedVertex = getBufferedVertex(vertexId).get
    bufferedVertex.edges.valuesIterator.flatten.foreach { case (destinationVertexId, edgeData) => removeEdge(vertexId, destinationVertexId, edgeData.kind) }

    val bufferedVertexIncomingEdges = getBufferedIncomingEdges(vertexId)
    bufferedVertexIncomingEdges.foreach { case ((_, _, edgeKind), sourceVertexIds) => sourceVertexIds.foreach { sourceVertexId => removeEdge(sourceVertexId, vertexId, edgeKind) } }

    bufferedVertices -= vertexId
    bufferedIncomingEdges -= vertexId
    vertexDeltas(vertexKind).decrementAndGet()
  }

  def saveEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], data: E)(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Boolean = {
    val sourceVertexId = VertexId(source)
    val destinationVertexId = VertexId(destination)
    Vertex.checkIfVertexExists(bufferedVertices)(sourceVertexId)
    Vertex.checkIfVertexExists(bufferedVertices)(destinationVertexId)

    val bufferedSourceVertex = getBufferedVertex(sourceVertexId).get
    val component = (sourceKind, destinationKind, data.kind)
    if (!bufferedSourceVertex.edges.contains(component)) { bufferedSourceVertex.edges += (component -> MutableMap()) }
    val isNewEdge = !bufferedSourceVertex.edges(component).contains(destinationVertexId)
    bufferedSourceVertex.edges(component) += (destinationVertexId -> data)
    if (isNewEdge) {
      val bufferedIncomingEdges = getBufferedIncomingEdges(destinationVertexId)
      if (!bufferedIncomingEdges.contains(component)) { bufferedIncomingEdges += (component -> MutableSet()) }
      bufferedIncomingEdges(component) += sourceVertexId
      edgeDeltas(component).incrementAndGet()
    }
    isNewEdge
  }

  def removeEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], edgeKind: EdgeKind[E])(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Unit = {
    val sourceVertexId = VertexId(source)
    val destinationVertexId = VertexId(destination)
    removeEdge(sourceVertexId, destinationVertexId, edgeKind)
  }

  private def removeEdge(sourceVertexId: VertexId, destinationVertexId: VertexId, edgeKind: EdgeType): Unit = {
    Vertex.checkIfEdgeExists(bufferedVertices)(sourceVertexId, destinationVertexId, edgeKind)
    val component = (sourceVertexId.kind, destinationVertexId.kind, edgeKind)
    val bufferedSourceVertex = getBufferedVertex(sourceVertexId).get

    bufferedSourceVertex.edges(component) -= destinationVertexId
    if (bufferedSourceVertex.edges(component).isEmpty) { bufferedSourceVertex.edges -= component }

    val bufferedIncomingEdges = getBufferedIncomingEdges(destinationVertexId)
    bufferedIncomingEdges(component) -= sourceVertexId
    if (bufferedIncomingEdges(component).isEmpty) { bufferedIncomingEdges -= component }

    edgeDeltas(component).decrementAndGet()
  }

  def commit(): Unit = {
    val commitStatistics = GraphStatistics.filter(vertexDeltas, edgeDeltas)
    bufferedVertices.flush()
    bufferedIncomingEdges.flush()
    vertexDeltas.foreach { case (vertexKind, counter) => vertexStatistics(vertexKind).addAndGet(counter.getAndSet(0)) }
    edgeDeltas.foreach { case (component, counter) => edgeStatistics(component).addAndGet(counter.getAndSet(0)) }
    log.info(s"Graph commit: $commitStatistics")
  }
}

class BufferedMap[A, B](current: MutableMap[A, B], updated: MutableMap[A, B] = MutableMap[A, B](), removed: MutableSet[A] = MutableSet[A]()) extends MutableMap[A, B] {
  def get(key: A) = if (removed.contains(key)) None else updated.get(key) orElse current.get(key)
  def iterator = updated.iterator ++ current.iterator.withFilter { case (key, _) => !hasBuffered(key) }
  def +=(kv: (A, B)) = {
    removed -= kv._1
    updated += kv
    this
  }
  def -=(key: A) = {
    removed += key
    updated -= key
    this
  }
  def flush(): Unit = {
    current --= removed
    current ++= updated
  }
  def hasBuffered(key: A): Boolean = { updated.contains(key) || removed.contains(key) }
}