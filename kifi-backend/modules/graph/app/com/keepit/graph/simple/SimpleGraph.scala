package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.concurrent.{Map => ConcurrentMap, TrieMap}

class SimpleGraph(vertices: ConcurrentMap[VertexId, MutableVertex]) extends GraphReaderImpl(vertices) with GraphManager {
  def write(f: GraphWriter => Unit): Unit = {
    val bufferedVertices = new BufferedMap(vertices)
    val graphWriter = new GraphWriterImpl(bufferedVertices)
    f(graphWriter)
    graphWriter.commit()
  }
}

object SimpleGraph {
  def apply() = new SimpleGraph(TrieMap())
}
