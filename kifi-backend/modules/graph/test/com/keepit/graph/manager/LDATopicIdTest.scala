package com.keepit.graph.manager

import org.specs2.mutable.Specification
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ DenseLDA, LDATopic }

class LDATopicIdTest extends Specification {
  "LDATopicId" should {
    "serialize to long" in {
      val version = ModelVersion[DenseLDA](1)
      val topic = LDATopic(1234567890)
      val topicId = LDATopicId(version, topic)
      LDATopicId.fromLong(LDATopicId.toLong(topicId)) === topicId

      val maxVersion = ModelVersion[DenseLDA](LDATopicId.maxVersion.toInt)
      val maxTopic = LDATopic(LDATopicId.maxTopic.toInt)
      val maxTopicId = LDATopicId(maxVersion, maxTopic)
      LDATopicId.fromLong(LDATopicId.toLong(maxTopicId)) === maxTopicId
    }
  }
}
