package com.keepit.graph.concurrent

import com.keepit.graph.model._
import scala.collection.Map

trait Vertex {
  def data: VertexDataReader
  def edges: Map[VertexId, EdgeDataReader]
}

class VertexReaderException(message: String) extends Throwable(message)
class EdgeReaderException(message: String) extends Throwable(message)

class GlobalVertexReaderImpl(vertices: Map[VertexId, Vertex]) extends GlobalVertexReader {
  private var currentVertexId: Option[VertexId] = None
  private def currentVertex: Vertex = vertices(id)
  def id: VertexId = currentVertexId getOrElse { throw new VertexReaderException(s"$this is not initialized over a valid vertex") }
  def data: VertexDataReader = currentVertex.data
  def kind: VertexKind[_ <: VertexDataReader] = data.kind
  val edgeReader: LocalEdgeReader = new LocalEdgeReaderImpl(this, currentVertex.edges)
  def moveTo(vertex: VertexId): Unit = {
    currentVertexId = Some(vertex)
    edgeReader.reset()
  }
  def moveTo[V <: VertexDataReader: VertexKind](vertex: VertexDataId[V]): Unit = { moveTo(VertexId(vertex)) }
}

class LocalEdgeReaderImpl(owner: VertexReader, edges: => Map[VertexId, EdgeDataReader]) extends LocalEdgeReader {
  private var destinations: Option[Iterator[VertexId]] = None
  private var currentDestination: Option[VertexId] = None
  def source: VertexId = owner.id
  def sourceVertex = owner
  def destination: VertexId = currentDestination getOrElse { throw new EdgeReaderException(s"$this is not initialized over a valid destination vertex") }
  def data: EdgeDataReader = edges(destination)
  def kind: EdgeKind[_ <: EdgeDataReader] = data.kind
  def degree = edges.size
  def moveToNextEdge(): Boolean = destinations match {
    case Some(iterator) if iterator.hasNext => currentDestination = Some(iterator.next()); true
    case _ => false
  }
  def reset(): Unit = {
    destinations = Some(edges.keysIterator)
    currentDestination = None
  }
}

class GlobalEdgeReaderImpl(vertices: Map[VertexId, Vertex]) extends GlobalEdgeReader {
  private val globalSourceReader = new GlobalVertexReaderImpl(vertices)
  private val globalDestinationReader = new GlobalVertexReaderImpl(vertices)

  def kind: EdgeKind[_ <: EdgeDataReader] = data.kind
  def source: VertexId = globalSourceReader.id
  def destination: VertexId = globalDestinationReader.id
  def data: EdgeDataReader = vertices(source).edges(destination)

  def sourceVertex: VertexReader = globalSourceReader
  def destinationVertex: VertexReader = globalDestinationReader
  def moveTo(source: VertexId, destination: VertexId): Unit = {
    globalSourceReader.moveTo(source)
    globalDestinationReader.moveTo(destination)
  }
  def moveTo[S <: VertexDataReader: VertexKind, D <: VertexDataReader: VertexKind](source: VertexDataId[S], destination: VertexDataId[D]): Unit = { moveTo(VertexId(source), VertexId(destination)) }
}

class GraphReaderImpl(vertices: Map[VertexId, Vertex]) extends GraphReader {
  def getNewVertexReader(): GlobalVertexReader = new GlobalVertexReaderImpl(vertices)
  def getNewEdgeReader(): GlobalEdgeReader = new GlobalEdgeReaderImpl(vertices)
}