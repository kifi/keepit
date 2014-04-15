package com.keepit.graph.model

import org.specs2.mutable.Specification

class GraphTypeSystemTest extends Specification {

  "Graph" should {
    "instantiate consistent VertexDataReaders" in {
      VertexDataReader
      VertexKind.all must not be empty
    }

    "instantiate consistent EdgeDataReaders" in {
      EdgeDataReader
      EdgeKind.all must not be empty
    }
  }
}
