package com.keepit.graph.concurrent

import com.keepit.graph.model._
import scala.collection.mutable.{Map => MutableMap}

class SimpleGraph(vertices: MutableMap[VertexId, MutableVertex]) extends GraphReaderImpl(vertices) with GraphManager {
  def write(f: GraphWriter => Unit): Unit = {
    val bufferedVertices = new BufferedMap(vertices)
    val graphWriter = new GraphWriterImpl(bufferedVertices)
    f(graphWriter)
    graphWriter.commit()
  }
}
