package com.keepit.graph.model

import org.specs2.mutable.Specification

class GraphManagerTest extends Specification {

  "GraphManager" should {
    "instantiate consistent VertexDataReaders" in {
      VertexKind
      VertexDataReader
      "All good" === "All good"
    }

    "instantiate consistent EdgeDataReaders" in {
      EdgeKind
      EdgeDataReader
      "All good" === "All good"
    }
  }
}
