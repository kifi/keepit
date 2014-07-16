package com.keepit.graph.simple

import scala.collection.concurrent.{ Map => ConcurrentMap, TrieMap }
import scala.collection.mutable.{ Map => MutableMap, Set => MutableSet }
import com.keepit.graph.model._
import play.api.libs.json._
import com.keepit.graph.manager.GraphStatistics
import java.io.File
import org.apache.commons.io.{ LineIterator, FileUtils }
import scala.collection.JavaConversions._

case class SimpleGraph(vertices: ConcurrentMap[VertexId, MutableVertex] = TrieMap()) {

  private val vertexStatistics = GraphStatistics.newVertexCounter()
  private val edgeStatistics = GraphStatistics.newEdgeCounter()
  private val incomingEdges = TrieMap[VertexId, MutableIncomingEdges]()

  vertices.foreach {
    case (sourceId, mutableVertex) =>
      val sourceKind = mutableVertex.data.kind
      vertexStatistics(sourceKind).incrementAndGet()
      mutableVertex.outgoingEdges.edges.valuesIterator.flatten.foreach {
        case (destinationId, edgeData) =>
          val component = (sourceKind, destinationId.kind, edgeData.kind)
          edgeStatistics(component).incrementAndGet()
          if (!incomingEdges.contains(destinationId)) { incomingEdges += (destinationId -> new MutableIncomingEdges(MutableMap())) }
          if (!incomingEdges(destinationId).edges.contains(component)) { incomingEdges(destinationId).edges += (component -> MutableSet()) }
          incomingEdges(destinationId).edges(component) += sourceId
      }
  }

  def statistics = GraphStatistics.filter(vertexStatistics, edgeStatistics)

  def getNewReader(): GraphReader = new SimpleGraphReader(vertices, incomingEdges)

  def getNewWriter(): GraphWriter = {
    val bufferedVertices = new BufferedMap(vertices)
    val bufferedIncomingEdges = new BufferedMap(incomingEdges)
    new SimpleGraphWriter(bufferedVertices, bufferedIncomingEdges, vertexStatistics, edgeStatistics)
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
  def write(graph: SimpleGraph, graphFile: File): Unit = {
    val lines: Iterable[String] = graph.vertices.map {
      case (vertexId, vertex) => Json.stringify(
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
          vertices += (vertexId -> vertex)
      }
    } finally {
      LineIterator.closeQuietly(lineIterator)
    }
    SimpleGraph(vertices)
  }
}
