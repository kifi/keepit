package com.keepit.graph.model

import org.specs2.mutable.Specification

class VertexIdTest extends Specification {
  "VertexId" should {
    "recover typed ids" in {
      val userId = VertexDataId[UserReader](1234567890L)
      val vertexId = VertexId(userId)
      vertexId.asId[UserReader] === userId
      vertexId.asId[UriReader] must throwA[InvalidVertexIdException[UriReader]]
      vertexId.asIdOpt[UserReader] === Some(userId)
      vertexId.asIdOpt[UriReader] === None
    }
  }
}
