package com.keepit.graph.simple

import scala.collection.concurrent.{Map => ConcurrentMap, TrieMap}
import com.keepit.graph.model.{GraphWriter, GraphReader, VertexId}
import play.api.libs.json.{JsResult, JsArray, JsValue, Format}


case class SimpleGraph(vertices: ConcurrentMap[VertexId, MutableVertex] = TrieMap()) {

  def getNewReader(): GraphReader = new GraphReaderImpl(vertices)

  def getNewWriter(): GraphWriter = {
    val bufferedVertices = new BufferedMap(vertices)
    new GraphWriterImpl(bufferedVertices)
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
