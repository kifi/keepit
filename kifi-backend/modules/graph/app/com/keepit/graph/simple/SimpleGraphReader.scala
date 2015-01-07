package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.Map
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.graph.model.EdgeKind.EdgeType

class SimpleGlobalVertexReader(vertices: Map[VertexId, Vertex]) extends GlobalVertexReader {
  private var currentVertexId: Option[VertexId] = None
  private def currentVertex: Vertex = vertices(id)
  @inline private def edgeData(source: VertexId, destination: VertexId, component: (VertexType, VertexType, EdgeType)) = vertices(source).outgoingEdges.edges(component)(destination)
  def id: VertexId = currentVertexId getOrElse { throw new UninitializedReaderException(s"$this is not initialized over a valid vertex") }
  def data: VertexDataReader = currentVertex.data
  def kind: VertexType = data.kind
  val outgoingEdgeReader: OutgoingEdgeReader = new SimpleOutgoingEdgeReader(this, currentVertex.outgoingEdges)
  val incomingEdgeReader: IncomingEdgeReader = new SimpleIncomingEdgeReader(this, currentVertex.incomingEdges, edgeData)
  def hasVertex(vertexId: VertexId) = vertices.contains(vertexId)
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
  private[this] var components: Iterator[(VertexType, VertexType, EdgeType)] = null
  private[this] var currentComponent: (VertexType, VertexType, EdgeType) = null
  private[this] var edges: Iterator[(VertexId, VertexId, EdgeDataReader)] = null
  private[this] var currentEdge: (VertexId, VertexId, EdgeDataReader) = null
  private def edge: (VertexId, VertexId, EdgeDataReader) = {
    if (currentEdge == null) throw new UninitializedReaderException(s"$this is not initialized over a valid edge")
    currentEdge
  }
  def component: (VertexType, VertexType, EdgeType) = {
    if (currentComponent == null) throw new UninitializedReaderException(s"$this is not initialized over a valid component")
    currentComponent
  }
  def source: VertexId = edge._1
  def destination: VertexId = edge._2
  def data: EdgeDataReader = edge._3
  def kind: EdgeType = component._3
  def moveToNextComponent(): Boolean = {
    if (components == null) components = getComponentIterator()
    if (components.hasNext) {
      currentComponent = components.next()
      edges = null
      true
    } else {
      false
    }
  }
  def moveToNextEdge(): Boolean = {
    if (edges == null) edges = getEdgeIterator()
    if (edges.hasNext) {
      currentEdge = edges.next()
      true
    } else {
      false
    }
  }
  def reset(): Unit = {
    components = null
    edges = null
    currentComponent = null
    currentEdge = null
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

class SimpleGlobalEdgeReader(vertices: Map[VertexId, Vertex]) extends GlobalEdgeReader {
  private[this] val globalSourceReader = new SimpleGlobalVertexReader(vertices)
  private[this] val globalDestinationReader = new SimpleGlobalVertexReader(vertices)
  private[this] var currentEdgeKind: EdgeType = null

  def kind: EdgeType = {
    if (currentEdgeKind == null) throw new UninitializedReaderException(s"$this is not initialized over a valid edge")
    currentEdgeKind
  }
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
    currentEdgeKind = edgeKind
  }
}

class SimpleGraphReader(vertices: Map[VertexId, Vertex]) extends GraphReader {
  def getNewVertexReader(): GlobalVertexReader = new SimpleGlobalVertexReader(vertices)
  def getNewEdgeReader(): GlobalEdgeReader = new SimpleGlobalEdgeReader(vertices)
}
