package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.{ Map, Set }
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.graph.model.EdgeKind.EdgeType

trait Vertex {
  def data: VertexDataReader
  def outgoingEdges: OutgoingEdges
}

trait OutgoingEdges {
  def edges: Map[(VertexType, VertexType, EdgeType), Map[VertexId, EdgeDataReader]]
}

trait IncomingEdges {
  def edges: Map[(VertexType, VertexType, EdgeType), Set[VertexId]]
}

object Vertex {
  def checkIfVertexExists(vertices: Map[VertexId, Vertex])(vertexId: VertexId): Unit = {
    if (!vertices.contains(vertexId)) { throw new VertexNotFoundException(vertexId) }
  }

  def checkIfEdgeExists(vertices: Map[VertexId, Vertex])(sourceVertexId: VertexId, destinationVertexId: VertexId, edgeKind: EdgeType): Unit = {
    checkIfVertexExists(vertices)(sourceVertexId)
    checkIfVertexExists(vertices)(destinationVertexId)
    val component = (sourceVertexId.kind, destinationVertexId.kind, edgeKind)
    if (!vertices(sourceVertexId).outgoingEdges.edges.contains(component) || !vertices(sourceVertexId).outgoingEdges.edges(component).contains(destinationVertexId)) {
      throw new EdgeNotFoundException(sourceVertexId, destinationVertexId, edgeKind)
    }
  }
}

class SimpleGlobalVertexReader(vertices: Map[VertexId, Vertex], incomingEdges: Map[VertexId, IncomingEdges]) extends GlobalVertexReader {
  private var currentVertexId: Option[VertexId] = None
  private def currentVertex: Vertex = vertices(id)
  private def edgeData(source: VertexId, destination: VertexId, component: (VertexType, VertexType, EdgeType)) = vertices(source).outgoingEdges.edges(component)(destination)
  def id: VertexId = currentVertexId getOrElse { throw new UninitializedReaderException(s"$this is not initialized over a valid vertex") }
  def data: VertexDataReader = currentVertex.data
  def kind: VertexKind[_ <: VertexDataReader] = data.kind
  val outgoingEdgeReader: OutgoingEdgeReader = new SimpleOutgoingEdgeReader(this, currentVertex.outgoingEdges)
  val incomingEdgeReader: IncomingEdgeReader = new SimpleIncomingEdgeReader(this, incomingEdges(id), edgeData)
  def moveTo(vertex: VertexId): Unit = {
    Vertex.checkIfVertexExists(vertices)(vertex)
    currentVertexId = Some(vertex)
    outgoingEdgeReader.reset()
    incomingEdgeReader.reset()
  }
  def moveTo[V <: VertexDataReader: VertexKind](vertex: VertexDataId[V]): Unit = { moveTo(VertexId(vertex)) }
}

trait SimpleLocalEdgeReader extends LocalEdgeReader {
  protected def getComponentIterator(): Iterator[(VertexType, VertexType, EdgeType)]
  protected def getEdgeIterator(): Iterator[(VertexId, VertexId, EdgeDataReader)]
  private var components: Option[Iterator[(VertexType, VertexType, EdgeType)]] = None
  private var currentComponent: Option[(VertexType, VertexType, EdgeType)] = None
  private var edges: Option[Iterator[(VertexId, VertexId, EdgeDataReader)]] = None
  private var currentEdge: Option[(VertexId, VertexId, EdgeDataReader)] = None
  def component: (VertexType, VertexType, EdgeType) = currentComponent getOrElse { throw new UninitializedReaderException(s"$this is not initialized over a valid component") }
  def source: VertexId = currentEdge.get._1
  def destination: VertexId = currentEdge.get._2
  def data: EdgeDataReader = currentEdge.get._3
  def kind: EdgeKind[_ <: EdgeDataReader] = component._3
  def moveToNextComponent(): Boolean = components match {
    case None => throw new UninitializedReaderException(s"$this is not initialized over a valid vertex")
    case Some(iterator) if iterator.hasNext => {
      currentComponent = Some(iterator.next())
      edges = Some(getEdgeIterator())
      true
    }
    case _ => false
  }
  def moveToNextEdge(): Boolean = edges match {
    case None => throw new UninitializedReaderException(s"$this is not initialized over a valid component")
    case Some(iterator) if iterator.hasNext =>
      currentEdge = Some(iterator.next()); true
    case _ => false
  }
  def reset(): Unit = {
    components = Some(getComponentIterator())
    currentComponent = None
    currentEdge = None
  }
}

class SimpleOutgoingEdgeReader(owner: VertexReader, outgoingEdges: => OutgoingEdges) extends SimpleLocalEdgeReader with OutgoingEdgeReader {
  protected def getComponentIterator(): Iterator[(VertexType, VertexType, EdgeType)] = outgoingEdges.edges.keysIterator
  protected def getEdgeIterator(): Iterator[(VertexId, VertexId, EdgeDataReader)] = outgoingEdges.edges(component).iterator.map {
    case (destinationId, edgeData) => (owner.id, destinationId, edgeData)
  }
  def degree: Int = outgoingEdges.edges(component).size
  def sourceVertex = owner
}

class SimpleIncomingEdgeReader(owner: VertexReader, incomingEdges: => IncomingEdges, edgeData: (VertexId, VertexId, (VertexType, VertexType, EdgeType)) => EdgeDataReader) extends SimpleLocalEdgeReader with IncomingEdgeReader {
  protected def getComponentIterator(): Iterator[(VertexType, VertexType, EdgeType)] = incomingEdges.edges.keysIterator
  protected def getEdgeIterator(): Iterator[(VertexId, VertexId, EdgeDataReader)] = incomingEdges.edges(component).iterator.map {
    sourceId => (sourceId, owner.id, edgeData(sourceId, owner.id, component))
  }
  def degree: Int = incomingEdges.edges(component).size
  def destinationVertex = owner
}

class SimpleGlobalEdgeReader(vertices: Map[VertexId, Vertex], incomingEdges: Map[VertexId, IncomingEdges]) extends GlobalEdgeReader {
  private val globalSourceReader = new SimpleGlobalVertexReader(vertices, incomingEdges)
  private val globalDestinationReader = new SimpleGlobalVertexReader(vertices, incomingEdges)
  private var currentEdgeKind: Option[EdgeType] = None

  def kind: EdgeKind[_ <: EdgeDataReader] = currentEdgeKind getOrElse { throw new UninitializedReaderException(s"$this is not initialized over a valid edge") }
  def source: VertexId = globalSourceReader.id
  def destination: VertexId = globalDestinationReader.id
  private def component = (source.kind, destination.kind, kind)
  def data: EdgeDataReader = vertices(source).outgoingEdges.edges(component)(destination)

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

class SimpleGraphReader(vertices: Map[VertexId, Vertex], incomingEdges: Map[VertexId, IncomingEdges]) extends GraphReader {
  def getNewVertexReader(): GlobalVertexReader = new SimpleGlobalVertexReader(vertices, incomingEdges)
  def getNewEdgeReader(): GlobalEdgeReader = new SimpleGlobalEdgeReader(vertices, incomingEdges)
}