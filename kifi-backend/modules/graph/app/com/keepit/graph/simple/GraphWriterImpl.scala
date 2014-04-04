package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.mutable.{Map => MutableMap, Set => MutableSet}

class MutableVertex(var data: VertexDataReader, val edges: MutableMap[VertexId, EdgeDataReader]) extends Vertex

class GraphWriterImpl(bufferedVertices: BufferedMap[VertexId, MutableVertex]) extends GraphReaderImpl(bufferedVertices) with GraphWriter {

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
      case Some(bufferedVertex) => bufferedVertex.data = data; true
      case None => bufferedVertices += (vertexId -> new MutableVertex(data, MutableMap())); false
    }
  }

  def saveEdge[S <: VertexDataReader: VertexKind, D <: VertexDataReader: VertexKind, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], data: E): Boolean = {
    val sourceVertexId = VertexId(source)
    val bufferedSourceVertex = getBufferedVertex(sourceVertexId).get
    val destinationVertexId = VertexId(destination)
    val isUpdate = bufferedSourceVertex.edges.contains(destinationVertexId)
    bufferedSourceVertex.edges += (destinationVertexId -> data)
    isUpdate
  }

  def removeEdge[S <: VertexDataReader: VertexKind, D <: VertexDataReader: VertexKind](source: VertexDataId[S], destination: VertexDataId[D]): Boolean = {
    val sourceVertexId = VertexId(source)
    val bufferedSourceVertex = getBufferedVertex(sourceVertexId).get
    val destinationVertexId = VertexId(destination)
    val isUpdate = bufferedSourceVertex.edges.contains(destinationVertexId)
    bufferedSourceVertex.edges -= destinationVertexId
    isUpdate
  }

  def commit(): Unit = { bufferedVertices.flush() }
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