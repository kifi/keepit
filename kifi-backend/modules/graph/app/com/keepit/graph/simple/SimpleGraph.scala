package com.keepit.graph.simple

import scala.collection.concurrent.{ Map => ConcurrentMap, TrieMap }
import com.keepit.graph.model._
import play.api.libs.json._
import com.keepit.graph.manager.GraphStatistics
import java.io.File
import org.apache.commons.io.{ LineIterator, FileUtils }
import scala.collection.JavaConversions._

case class SimpleGraph(vertices: ConcurrentMap[VertexId, MutableVertex] = TrieMap()) {

  vertices.foreach { case (vertexId, vertex) => SimpleGraph.checkVertexIntegrity(this, vertexId, vertex) }

  private val (vertexStatistics, edgeStatistics) = SimpleGraph.initializeGraphStatistics(vertices.valuesIterator)

  def statistics = GraphStatistics.filter(vertexStatistics, edgeStatistics)

  def getNewReader(): GraphReader = new SimpleGraphReader(vertices)

  def getNewWriter(): GraphWriter = {
    val bufferedVertices = new BufferedMap(vertices)
    new SimpleGraphWriter(bufferedVertices, vertexStatistics, edgeStatistics)
  }

  def readWrite[T](f: GraphWriter => T): T = {
    val graphWriter = getNewWriter()
    val result = f(graphWriter)
    this.synchronized { graphWriter.commit() }
    result
  }

  private val reusableGraphReader = getNewReader()
  def readOnly[T](f: GraphReader => T): T = f(reusableGraphReader)
}

object SimpleGraph {
  implicit val vertexFormat = MutableVertex.lossyFormat

  def write(graph: SimpleGraph, graphFile: File): Unit = {
    val lines: Iterable[String] = graph.vertices.toIterable.map {
      case (vertexId, vertex) =>
        checkVertexIntegrity(graph, vertexId, vertex)
        Json.stringify(
          Json.arr(JsNumber(vertexId.id), Json.toJson(vertex))
        )
    }
    FileUtils.writeLines(graphFile, lines)
  }

  def read(graphFile: File): SimpleGraph = {
    val vertices = TrieMap[VertexId, MutableVertex]()
    val lineIterator = FileUtils.lineIterator(graphFile)
    try {
      lineIterator.foreach {
        case line =>
          val JsArray(Seq(idJson, vertexJson)) = Json.parse(line)
          val vertexId = idJson.as[VertexId]
          val vertex = vertexJson.as[MutableVertex]
          require(vertex.data.kind == vertexId.kind, s"Inconsistent serialized data for vertex $vertexId: ${vertex.data}")
          vertices += (vertexId -> vertex)
      }
      MutableVertex.initializeIncomingEdges(vertices)
    } finally {
      LineIterator.closeQuietly(lineIterator)
    }
    SimpleGraph(vertices)
  }

  def checkVertexIntegrity(graph: SimpleGraph, vertexId: VertexId, vertex: Vertex): Unit = {

    if (vertex.data.kind != vertexId.kind || vertex.data.id != vertexId.asId(vertexId.kind)) {
      throw new IllegalStateException(s"Invalid vertex id $vertexId for vertex of kind ${vertex.data.kind} with id ${vertex.data.id}")
    }

    vertex.outgoingEdges.edges.foreach {
      case (component, destinationIds) =>
        if (vertexId.kind != component._1) { throw new IllegalStateException(s"Invalid source kind for outgoing component $component in vertex $vertexId") }
        destinationIds.foreach {
          case (destinationId, edgeData) =>
            if (!graph.vertices.contains(destinationId)) { throw new IllegalStateException(s"Could not find destination vertex of outgoing edge ${(vertexId, destinationId, edgeData.kind)}") }
            if (destinationId.kind != component._2) { throw new IllegalStateException(s"Invalid destination kind for outgoing edge ${(vertexId, destinationId, edgeData.kind)} in component $component") }
            if (edgeData.kind != component._3) { throw new IllegalStateException(s"Invalid edge data kind for outgoing edge ${(vertexId, destinationId, edgeData.kind)} in component $component") }
        }
    }

    vertex.incomingEdges.edges.foreach {
      case (component, sourcesIds) =>
        if (vertexId.kind != component._2) { throw new IllegalStateException(s"Invalid destination kind for incoming component $component in vertex $vertexId") }
        sourcesIds.foreach { sourceId =>
          if (!graph.vertices.contains(sourceId)) { throw new IllegalStateException(s"Could not find source vertex of incoming edge ${(sourceId, vertexId, component._3)}") }
          if (sourceId.kind != component._1) { throw new IllegalStateException(s"Invalid source kind for incoming edge ${(sourceId, vertexId, component._3)} in component $component") }
        }
    }
  }

  def initializeGraphStatistics(vertices: Iterator[Vertex]) = {
    val vertexStatistics = GraphStatistics.newVertexCounter()
    val edgeStatistics = GraphStatistics.newEdgeCounter()
    vertices.foreach { vertex =>
      vertexStatistics(vertex.data.kind).incrementAndGet()
      vertex.outgoingEdges.edges.foreach {
        case (component, destinations) =>
          edgeStatistics(component).addAndGet(destinations.size)
      }
    }
    (vertexStatistics, edgeStatistics)
  }
}
