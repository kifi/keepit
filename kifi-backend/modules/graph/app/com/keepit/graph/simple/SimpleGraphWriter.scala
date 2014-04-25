package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.mutable.{Map => MutableMap, Set => MutableSet}
import play.api.libs.json._
import java.util.concurrent.atomic.AtomicLong
import com.keepit.graph.manager.GraphStatistics.{EdgeType, VertexType}
import com.keepit.graph.manager.GraphStatistics

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

class SimpleGraphWriter(bufferedVertices: BufferedMap[VertexId, MutableVertex], vertexStatistics: Map[VertexType, AtomicLong], edgeStatistics: Map[EdgeType, AtomicLong]) extends SimpleGraphReader(bufferedVertices) with GraphWriter {

  private val vertexDeltas = GraphStatistics.newVertexCounter()
  private val edgeDeltas: Map[EdgeType, AtomicLong] = GraphStatistics.newEdgeCounter()

  private def getBufferedVertex(vertexId: VertexId): Option[MutableVertex] = bufferedVertices.get(vertexId) map {
    case alreadyBufferedVertex if bufferedVertices.hasBuffered(vertexId) => alreadyBufferedVertex
    case vertex => {
      val buffered = new MutableVertex(vertex.data, MutableMap() ++= vertex.edges)
      bufferedVertices += (vertexId -> buffered)
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

  def saveEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], data: E)(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Boolean = {
    val sourceVertexId = VertexId(source)
    val bufferedSourceVertex = getBufferedVertex(sourceVertexId) getOrElse { throw new VertexNotFoundException(sourceVertexId) }
    val destinationVertexId = VertexId(destination)
    if (!bufferedVertices.contains(destinationVertexId)) { throw new VertexNotFoundException(destinationVertexId) }
    val isNewEdge = !bufferedSourceVertex.edges.contains(destinationVertexId)
    bufferedSourceVertex.edges += (destinationVertexId -> data)
    if (isNewEdge) { edgeDeltas((sourceKind, destinationKind, data.kind)).incrementAndGet() }
    isNewEdge
  }

  def removeEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], edgeKind: EdgeKind[E])(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Unit = {
    val sourceVertexId = VertexId(source)
    val bufferedSourceVertex = getBufferedVertex(sourceVertexId) getOrElse { throw new VertexNotFoundException(sourceVertexId) }
    val destinationVertexId = VertexId(destination)
    if (!bufferedVertices.contains(destinationVertexId)) { throw new VertexNotFoundException(destinationVertexId) }
    if (!bufferedSourceVertex.edges.contains(destinationVertexId)) { throw new EdgeNotFoundException(sourceVertexId, destinationVertexId) }
    bufferedSourceVertex.edges -= destinationVertexId
    edgeDeltas((sourceKind, destinationKind, edgeKind)).decrementAndGet()
  }

  def commit(): Unit = {
    bufferedVertices.flush()
    vertexDeltas.foreach { case (vertexKind, counter) => vertexStatistics(vertexKind).addAndGet(counter.getAndSet(0)) }
    edgeDeltas.foreach { case (edgeKinds, counter) => edgeStatistics(edgeKinds).addAndGet(counter.getAndSet(0)) }
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