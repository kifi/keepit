package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.Map

trait Vertex {
  def data: VertexDataReader
  def edges: Map[VertexId, EdgeDataReader]
}

class SimpleGlobalVertexReader(vertices: Map[VertexId, Vertex]) extends GlobalVertexReader {
  private var currentVertexId: Option[VertexId] = None
  private def currentVertex: Vertex = vertices(id)
  def id: VertexId = currentVertexId getOrElse { throw new UninitializedReaderException(s"$this is not initialized over a valid vertex") }
  def data: VertexDataReader = currentVertex.data
  def kind: VertexKind[_ <: VertexDataReader] = data.kind
  val edgeReader: LocalEdgeReader = new SimpleLocalEdgeReader(this, currentVertex.edges)
  def moveTo(vertex: VertexId): Unit = {
    if (!vertices.contains(vertex)) { throw new VertexNotFoundException(vertex) }
    currentVertexId = Some(vertex)
    edgeReader.reset()
  }
  def moveTo[V <: VertexDataReader: VertexKind](vertex: VertexDataId[V]): Unit = { moveTo(VertexId(vertex)) }
}

class SimpleLocalEdgeReader(owner: VertexReader, edges: => Map[VertexId, EdgeDataReader]) extends LocalEdgeReader {
  private var destinations: Option[Iterator[VertexId]] = None
  private var currentDestination: Option[VertexId] = None
  def source: VertexId = owner.id
  def sourceVertex = owner
  def destination: VertexId = currentDestination getOrElse { throw new UninitializedReaderException(s"$this is not initialized over a valid destination vertex") }
  def data: EdgeDataReader = edges(destination)
  def kind: EdgeKind[_ <: EdgeDataReader] = data.kind
  def degree = edges.size
  def moveToNextEdge(): Boolean = destinations match {
    case None => throw new UninitializedReaderException(s"$this is not initialized over a valid source vertex")
    case Some(iterator) if iterator.hasNext => currentDestination = Some(iterator.next()); true
    case _ => false
  }
  def reset(): Unit = {
    destinations = Some(edges.keysIterator)
    currentDestination = None
  }
}

class SimpleGlobalEdgeReader(vertices: Map[VertexId, Vertex]) extends GlobalEdgeReader {
  private val globalSourceReader = new SimpleGlobalVertexReader(vertices)
  private val globalDestinationReader = new SimpleGlobalVertexReader(vertices)

  def kind: EdgeKind[_ <: EdgeDataReader] = data.kind
  def source: VertexId = globalSourceReader.id
  def destination: VertexId = globalDestinationReader.id
  def data: EdgeDataReader = vertices(source).edges(destination)

  def sourceVertex: VertexReader = globalSourceReader
  def destinationVertex: VertexReader = globalDestinationReader
  def moveTo(source: VertexId, destination: VertexId): Unit = {
    if (!vertices.contains(source)) { throw new VertexNotFoundException(source) }
    if (!vertices.contains(destination)) { throw new VertexNotFoundException(destination) }
    if (!vertices(source).edges.contains(destination)) { throw new EdgeNotFoundException(source, destination) }
    globalSourceReader.moveTo(source)
    globalDestinationReader.moveTo(destination)
  }
  def moveTo[S <: VertexDataReader: VertexKind, D <: VertexDataReader: VertexKind](source: VertexDataId[S], destination: VertexDataId[D]): Unit = { moveTo(VertexId(source), VertexId(destination)) }
}

class SimpleGraphReader(vertices: Map[VertexId, Vertex]) extends GraphReader {
  def getNewVertexReader(): GlobalVertexReader = new SimpleGlobalVertexReader(vertices)
  def getNewEdgeReader(): GlobalEdgeReader = new SimpleGlobalEdgeReader(vertices)
}