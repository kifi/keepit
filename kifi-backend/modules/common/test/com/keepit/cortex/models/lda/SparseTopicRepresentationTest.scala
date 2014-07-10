package com.keepit.cortex.models.lda

import org.specs2.mutable.Specification
import play.api.libs.json._

class SparseTopicRepresentationTest extends Specification {
  "SparseTopicRepresentation" should {
    "serialize" in {
      val topic = SparseTopicRepresentation(100, Map(LDATopic(1) -> 0.5f, LDATopic(10) -> 0.2f, LDATopic(50) -> 0.1f))
      val js = Json.toJson(topic)
      val topic2 = Json.fromJson[SparseTopicRepresentation](js).get
      topic2.topics.keySet.foreach { k =>
        topic2.topics(k) === topic.topics(k)
      }
      topic.dimension === topic2.dimension
    }
  }
}
