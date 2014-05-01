package com.keepit.graph.simple

import scala.collection.concurrent.{Map => ConcurrentMap, TrieMap}
import scala.collection.mutable.{Map => MutableMap}
import com.keepit.graph.model._
import play.api.libs.json.{JsResult, JsValue, Format}
import play.api.libs.json.JsArray
import com.keepit.graph.manager.GraphStatistics


case class SimpleGraph(vertices: ConcurrentMap[VertexId, MutableVertex] = TrieMap()) {

  private val vertexStatistics = GraphStatistics.newVertexCounter()
  private val edgeStatistics = GraphStatistics.newEdgeCounter()
  private val incomingEdges = TrieMap[VertexId, MutableMap[VertexId, EdgeKind[_ <: EdgeDataReader]]]()

  vertices.foreach { case (sourceId, mutableVertex) =>
    val sourceKind = mutableVertex.data.kind
    vertexStatistics(sourceKind).incrementAndGet()
    mutableVertex.edges.foreach { case (destinationId, edgeData) =>
      val edgeKinds = (sourceKind, destinationId.kind, edgeData.kind)
      edgeStatistics(edgeKinds).incrementAndGet()
      if (!incomingEdges.contains(destinationId)) { incomingEdges += (destinationId -> MutableMap()) }
      incomingEdges(destinationId) += (sourceId -> edgeData.kind)
    }
  }

  def statistics = GraphStatistics.filter(vertexStatistics, edgeStatistics)

  def getNewReader(): GraphReader = new SimpleGraphReader(vertices)

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
  implicit val format: Format[SimpleGraph] = new Format[SimpleGraph] {
    def writes(simpleGraph: SimpleGraph): JsValue = JsArray(simpleGraph.vertices.flatMap { case (vertexId, vertex) => Seq(VertexId.format.writes(vertexId), MutableVertex.format.writes(vertex)) }.toSeq)
    def reads(json: JsValue): JsResult[SimpleGraph] = json.validate[JsArray].map { jsArray =>
      val vertices = TrieMap[VertexId, MutableVertex]()
      vertices ++= jsArray.value.sliding(2,2).map { case Seq(vertexId, vertex) =>
        vertexId.as[VertexId] -> vertex.as[MutableVertex]
      }
      SimpleGraph(vertices)
    }
  }
}
