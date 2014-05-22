package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.mutable.{Map => MutableMap, Set => MutableSet}
import play.api.libs.json._
import java.util.concurrent.atomic.AtomicLong
import com.keepit.graph.manager.GraphStatistics
import com.keepit.common.logging.Logging
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.graph.model.EdgeKind.EdgeType

class MutableVertex(var data: VertexDataReader, val edges: MutableMap[VertexId, EdgeDataReader]) extends Vertex

object MutableVertex {

  implicit val format: Format[MutableVertex] = new Format[MutableVertex] {

    def writes(vertex: MutableVertex): JsValue = Json.obj(
      "data" -> VertexDataReader.writes.writes(vertex.data),
      "edges" -> JsArray(vertex.edges.flatMap { case (destinationId, edgeDataReader) => Seq(VertexId.format.writes(destinationId), EdgeDataReader.writes.writes(edgeDataReader)) }.toSeq)
    )
    def reads(json: JsValue): JsResult[MutableVertex] = for {
      data <- VertexDataReader.readsAsVertexData.reads(json \ "data")
      edges <- (json \ "edges").validate[JsArray].map { jsArray =>
        val edges = MutableMap[VertexId, EdgeDataReader]()
        edges ++= jsArray.value.sliding(2,2).map { case Seq(destinationId, edgeData) =>
          destinationId.as[VertexId] -> edgeData.as[EdgeData[_ <: EdgeDataReader]].asReader
        }
        edges
      }
    } yield new MutableVertex(data.asReader, edges)
  }
}

class SimpleGraphWriter(
  bufferedVertices: BufferedMap[VertexId, MutableVertex],
  bufferedIncomingEdges: BufferedMap[VertexId, MutableMap[VertexId, EdgeType]],
  vertexStatistics: Map[VertexType, AtomicLong],
  edgeStatistics: Map[(VertexType, VertexType, EdgeType), AtomicLong]
) extends SimpleGraphReader(bufferedVertices) with GraphWriter with Logging {

  private val vertexDeltas = GraphStatistics.newVertexCounter()
  private val edgeDeltas: Map[(VertexType, VertexType, EdgeType), AtomicLong] = GraphStatistics.newEdgeCounter()

  private def getBufferedVertex(vertexId: VertexId): Option[MutableVertex] = bufferedVertices.get(vertexId) map {
    case alreadyBufferedVertex if bufferedVertices.hasBuffered(vertexId) => alreadyBufferedVertex
    case vertex => {
      val buffered = new MutableVertex(vertex.data, MutableMap() ++= vertex.edges)
      bufferedVertices += (vertexId -> buffered)
      buffered
    }
  }

  private def getBufferedIncomingEdges(vertexId: VertexId): MutableMap[VertexId, EdgeType] = bufferedIncomingEdges.get(vertexId) match {
    case Some(alreadyBufferedIncomingEdges) if bufferedIncomingEdges.hasBuffered(vertexId) => alreadyBufferedIncomingEdges
    case incomingEdgesOption => {
      val buffered = MutableMap[VertexId, EdgeType]()
      incomingEdgesOption.foreach(buffered ++= _)
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

    val bufferedVertex = getBufferedVertex(vertexId) getOrElse { throw new VertexNotFoundException(vertexId) }
    bufferedVertex.edges.foreach { case (destinationVertexId, edgeData) => removeEdge(vertexId, destinationVertexId, edgeData.kind) }

    val bufferedVertexIncomingEdges = getBufferedIncomingEdges(vertexId)
    bufferedVertexIncomingEdges.foreach { case (sourceVertexId, edgeKind) => removeEdge(sourceVertexId, vertexId, edgeKind) }

    bufferedVertices -= vertexId
    bufferedIncomingEdges -= vertexId
    vertexDeltas(vertexKind).decrementAndGet()
  }

  def saveEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], data: E)(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Boolean = {
    val sourceVertexId = VertexId(source)
    val bufferedSourceVertex = getBufferedVertex(sourceVertexId) getOrElse { throw new VertexNotFoundException(sourceVertexId) }
    val destinationVertexId = VertexId(destination)
    if (!bufferedVertices.contains(destinationVertexId)) { throw new VertexNotFoundException(destinationVertexId) }
    val isNewEdge = !bufferedSourceVertex.edges.contains(destinationVertexId)
    bufferedSourceVertex.edges += (destinationVertexId -> data)
    if (isNewEdge) {
      getBufferedIncomingEdges(destinationVertexId) += (sourceVertexId -> data.kind)
      edgeDeltas((sourceKind, destinationKind, data.kind)).incrementAndGet()
    }
    isNewEdge
  }

  def removeEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], edgeKind: EdgeKind[E])(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Unit = {
    val sourceVertexId = VertexId(source)
    val destinationVertexId = VertexId(destination)
    removeEdge(sourceVertexId, destinationVertexId, edgeKind)
  }

  private def removeEdge[E <: EdgeDataReader](sourceVertexId: VertexId, destinationVertexId: VertexId, edgeKind: EdgeKind[E]): Unit = {
    val bufferedSourceVertex = getBufferedVertex(sourceVertexId) getOrElse { throw new VertexNotFoundException(sourceVertexId) }
    if (!bufferedVertices.contains(destinationVertexId)) { throw new VertexNotFoundException(destinationVertexId) }
    if (!bufferedSourceVertex.edges.contains(destinationVertexId)) { throw new EdgeNotFoundException(sourceVertexId, destinationVertexId) }
    bufferedSourceVertex.edges -= destinationVertexId
    getBufferedIncomingEdges(destinationVertexId) -= sourceVertexId
    edgeDeltas((sourceVertexId.kind, destinationVertexId.kind, edgeKind)).decrementAndGet()
  }

  def commit(): Unit = {
    val commitStatistics = GraphStatistics.filter(vertexDeltas, edgeDeltas)
    bufferedVertices.flush()
    bufferedIncomingEdges.flush()
    vertexDeltas.foreach { case (vertexKind, counter) => vertexStatistics(vertexKind).addAndGet(counter.getAndSet(0)) }
    edgeDeltas.foreach { case (edgeKinds, counter) => edgeStatistics(edgeKinds).addAndGet(counter.getAndSet(0)) }
    log.info(s"Graph commit: $commitStatistics")
  }
}

class BufferedMap[A, B](current: MutableMap[A, B], updated: MutableMap[A, B] = MutableMap[A, B](), removed: MutableSet[A] = MutableSet[A]()) extends MutableMap[A, B] {
  def get(key : A) = if (removed.contains(key)) None else updated.get(key) orElse current.get(key)
  def iterator = updated.iterator ++ current.iterator.withFilter { case (key, _) => !hasBuffered(key) }
  def +=(kv: (A,B)) = {
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