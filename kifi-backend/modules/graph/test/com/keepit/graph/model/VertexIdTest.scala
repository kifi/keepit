package com.keepit.graph.model

import org.specs2.mutable.Specification

class VertexIdTest extends Specification {
  "VertexId" should {
    "recover typed ids" in {
      val userId = VertexDataId[UserReader](1234567890L)
      val userVertexId = VertexId(userId)
      userVertexId.asId[UserReader] === userId
      userVertexId.asId[UriReader] must throwA[InvalidVertexIdException[UriReader]]
      userVertexId.asIdOpt[UserReader] === Some(userId)
      userVertexId.asIdOpt[UriReader] === None

      val maxUserId = VertexDataId[UserReader](VertexId.maxVertexDataId)
      val maxUserVertexId = VertexId(maxUserId)
      maxUserVertexId.asId[UserReader] === maxUserId
      maxUserVertexId.asId[UriReader] must throwA[InvalidVertexIdException[UriReader]]
      maxUserVertexId.asIdOpt[UserReader] === Some(maxUserId)
      maxUserVertexId.asIdOpt[UriReader] === None
    }
  }
}
