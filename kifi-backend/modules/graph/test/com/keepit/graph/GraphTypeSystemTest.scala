package com.keepit.graph

import org.specs2.mutable.Specification
import com.keepit.graph.manager.GraphUpdateKind
import com.keepit.graph.model.{ EdgeKind, EdgeDataReader, VertexKind, VertexDataReader }

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

    "instantiate consistent GraphUpdates" in {
      GraphUpdateKind.all must not be empty
    }
  }
}
