package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.Map
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.graph.model.EdgeKind.EdgeType

trait Vertex {
  def data: VertexDataReader
  def edges: Map[(VertexType, VertexType, EdgeType), Map[VertexId, EdgeDataReader]]
}

object Vertex {
  def checkIfVertexExists(vertices: Map[VertexId, Vertex])(vertexId: VertexId): Unit = {
    if (!vertices.contains(vertexId)) { throw new VertexNotFoundException(vertexId) }
  }

  def checkIfEdgeExists(vertices: Map[VertexId, Vertex])(sourceVertexId: VertexId, destinationVertexId: VertexId, edgeKind: EdgeType): Unit = {
    checkIfVertexExists(vertices)(sourceVertexId)
    checkIfVertexExists(vertices)(destinationVertexId)
    val component = (sourceVertexId.kind, destinationVertexId.kind, edgeKind)
    if (!vertices(sourceVertexId).edges.contains(component) || !vertices(sourceVertexId).edges(component).contains(destinationVertexId)) {
      throw new EdgeNotFoundException(sourceVertexId, destinationVertexId, edgeKind)
    }
  }
}

class SimpleGlobalVertexReader(vertices: Map[VertexId, Vertex]) extends GlobalVertexReader {
  private var currentVertexId: Option[VertexId] = None
  private def currentVertex: Vertex = vertices(id)
  def id: VertexId = currentVertexId getOrElse { throw new UninitializedReaderException(s"$this is not initialized over a valid vertex") }
  def data: VertexDataReader = currentVertex.data
  def kind: VertexKind[_ <: VertexDataReader] = data.kind
  val outgoingEdgeReader: OutgoingEdgeReader = new SimpleOutgoingEdgeReader(this, currentVertex.edges)
  def moveTo(vertex: VertexId): Unit = {
    Vertex.checkIfVertexExists(vertices)(vertex)
    currentVertexId = Some(vertex)
    outgoingEdgeReader.reset()
  }
  def moveTo[V <: VertexDataReader: VertexKind](vertex: VertexDataId[V]): Unit = { moveTo(VertexId(vertex)) }
}

class SimpleOutgoingEdgeReader(owner: VertexReader, edges: => Map[(VertexType, VertexType, EdgeType), Map[VertexId, EdgeDataReader]]) extends OutgoingEdgeReader {
  private var components: Option[Iterator[(VertexType, VertexType, EdgeType)]] = None
  private var currentComponent: Option[(VertexType, VertexType, EdgeType)] = None
  private var destinations: Option[Iterator[VertexId]] = None
  private var currentDestination: Option[VertexId] = None
  def source: VertexId = owner.id
  def sourceVertex = owner
  def component: (VertexType, VertexType, EdgeType) = currentComponent getOrElse { throw new UninitializedReaderException(s"$this is not initialized over a valid component") }
  def destination: VertexId = currentDestination getOrElse { throw new UninitializedReaderException(s"$this is not initialized over a valid destination vertex") }
  def data: EdgeDataReader = edges(component)(destination)
  def kind: EdgeKind[_ <: EdgeDataReader] = data.kind
  def moveToNextComponent(): Boolean = components match {
    case None => throw new UninitializedReaderException(s"$this is not initialized over a valid source vertex")
    case Some(iterator) if iterator.hasNext => {
      currentComponent = Some(iterator.next())
      destinations = Some(edges(component).keysIterator)
      true
    }
    case _ => false
  }
  def degree = edges(component).size
  def moveToNextEdge(): Boolean = destinations match {
    case None => throw new UninitializedReaderException(s"$this is not initialized over a valid component")
    case Some(iterator) if iterator.hasNext =>
      currentDestination = Some(iterator.next()); true
    case _ => false
  }
  def reset(): Unit = {
    components = Some(edges.keysIterator)
    currentComponent = None
  }
}

class SimpleGlobalEdgeReader(vertices: Map[VertexId, Vertex]) extends GlobalEdgeReader {
  private val globalSourceReader = new SimpleGlobalVertexReader(vertices)
  private val globalDestinationReader = new SimpleGlobalVertexReader(vertices)
  private var currentEdgeKind: Option[EdgeType] = None

  def kind: EdgeKind[_ <: EdgeDataReader] = currentEdgeKind getOrElse { throw new UninitializedReaderException(s"$this is not initialized over a valid edge") }
  def source: VertexId = globalSourceReader.id
  def destination: VertexId = globalDestinationReader.id
  private def component = (source.kind, destination.kind, kind)
  def data: EdgeDataReader = vertices(source).edges(component)(destination)

  def sourceVertex: VertexReader = globalSourceReader
  def destinationVertex: VertexReader = globalDestinationReader
  def moveTo[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], edgeKind: EdgeKind[E])(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Unit = {
    val sourceVertexId = VertexId(source)
    val destinationVertexId = VertexId(destination)
    Vertex.checkIfEdgeExists(vertices)(sourceVertexId, destinationVertexId, edgeKind)
    globalSourceReader.moveTo(source)
    globalDestinationReader.moveTo(destination)
    currentEdgeKind = Some(edgeKind)
  }
}

class SimpleGraphReader(vertices: Map[VertexId, Vertex]) extends GraphReader {
  def getNewVertexReader(): GlobalVertexReader = new SimpleGlobalVertexReader(vertices)
  def getNewEdgeReader(): GlobalEdgeReader = new SimpleGlobalEdgeReader(vertices)
}