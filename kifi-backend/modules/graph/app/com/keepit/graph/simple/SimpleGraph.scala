package com.keepit.graph.simple

import scala.collection.concurrent.{ Map => ConcurrentMap, TrieMap }
import scala.collection.Map
import com.keepit.graph.model._
import play.api.libs.json._
import com.keepit.graph.manager.GraphStatistics
import java.io.File
import org.apache.commons.io.{ LineIterator, FileUtils }
import scala.collection.JavaConversions._
import com.keepit.common.logging.Logging

case class SimpleGraph(vertices: ConcurrentMap[VertexId, MutableVertex] = TrieMap()) {

  vertices.foreach { case (vertexId, vertex) => SimpleGraph.checkVertexIntegrity(vertices, vertexId, vertex) }

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

object SimpleGraph extends Logging {
  implicit val vertexFormat = MutableVertex.lossyFormat

  def write(graph: SimpleGraph, graphFile: File): Unit = {
    val lines: Iterable[String] = graph.vertices.iterator.map {
      case (vertexId, vertex) =>
        checkVertexIntegrity(graph.vertices, vertexId, vertex)
        Json.stringify(
          Json.arr(JsNumber(vertexId.id), Json.toJson(vertex))
        )
    }.toIterable
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

  def checkVertexIntegrity(vertices: Map[VertexId, Vertex], vertexId: VertexId, vertex: Vertex): Unit = {

    var errors: Seq[Throwable] = Seq.empty

    if (vertex.data.kind != vertexId.kind || vertex.data.id != vertexId.asId(vertexId.kind)) {
      errors :+= new IllegalStateException(s"Invalid vertex id $vertexId for vertex of kind ${vertex.data.kind} with id ${vertex.data.id}")
    }

    vertex.outgoingEdges.edges.foreach {
      case (component, destinationIds) =>
        if (vertexId.kind != component._1) { errors :+= new IllegalStateException(s"Invalid source kind for outgoing component $component in vertex $vertexId") }
        destinationIds.foreach {
          case (destinationId, edgeData) =>
            if (!vertices.contains(destinationId)) { errors :+= new IllegalStateException(s"Could not find destination vertex of outgoing edge ${(vertexId, destinationId, edgeData.kind)}") }
            if (destinationId.kind != component._2) { errors :+= new IllegalStateException(s"Invalid destination kind for outgoing edge ${(vertexId, destinationId, edgeData.kind)} in component $component") }
            if (edgeData.kind != component._3) { errors :+= new IllegalStateException(s"Invalid edge data kind for outgoing edge ${(vertexId, destinationId, edgeData.kind)} in component $component") }
        }
    }

    vertex.incomingEdges.edges.foreach {
      case (component, sourcesIds) =>
        if (vertexId.kind != component._2) { errors :+= new IllegalStateException(s"Invalid destination kind for incoming component $component in vertex $vertexId") }
        sourcesIds.foreach { sourceId =>
          if (!vertices.contains(sourceId)) { errors :+= new IllegalStateException(s"Could not find source vertex of incoming edge ${(sourceId, vertexId, component._3)}") }
          if (sourceId.kind != component._1) { errors :+= new IllegalStateException(s"Invalid source kind for incoming edge ${(sourceId, vertexId, component._3)} in component $component") }
        }
    }

    if (errors.nonEmpty) {
      val message = s"${errors.length} problem(s) found with vertex $vertexId:"
      val errorMessages = errors.map(_.toString)
      log.error(message)
      errorMessages.foreach(log.error(_))
      throw new IllegalStateException((message :+ errors).mkString("\n"))
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
