package com.keepit.rover.article

import org.specs2.mutable.Specification
import com.keepit.graph.manager.GraphUpdateKind
import com.keepit.graph.model.{ EdgeKind, EdgeDataReader, VertexKind, VertexDataReader }

class ArticleTest extends Specification {
  "Article" should {
    "instantiate consistent Article types" in {
      ArticleKind.all must not be empty
    }
  }
}
