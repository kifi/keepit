package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.concurrent.{Map => ConcurrentMap, TrieMap}
import play.api.libs.json.{JsArray, JsResult, JsValue, Format}

class SimpleGraph(val vertices: ConcurrentMap[VertexId, MutableVertex]) extends GraphReaderImpl(vertices) with GraphManager { self =>
  def write(f: GraphWriter => Unit): Unit = {
    val bufferedVertices = new BufferedMap(vertices)
    val graphWriter = new GraphWriterImpl(bufferedVertices)
    f(graphWriter)
    self.synchronized {
      graphWriter.commit()
    }
  }
}

object SimpleGraph {
  def apply() = new SimpleGraph(TrieMap())

  implicit val format: Format[SimpleGraph] = new Format[SimpleGraph] {
    def writes(o: SimpleGraph): JsValue = JsArray(o.vertices.values.map(MutableVertex.format.writes).toSeq)
    def reads(json: JsValue): JsResult[SimpleGraph] = json.validate[JsArray].map { jsArray =>
      val mutableVertices = jsArray.value.map(_.as[MutableVertex])
      val vertices = TrieMap[VertexId, MutableVertex]()
      vertices ++= mutableVertices.map { mutableVertex =>
        val vertexData = mutableVertex.data
        (VertexId(vertexData.id)(vertexData.kind) -> mutableVertex)
      }
      new SimpleGraph(vertices)
    }
  }
}
