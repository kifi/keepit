package com.keepit.cortex.lda

import org.specs2.mutable.Specification

class LDATopicIdTest extends Specification {
  "LDATopicId" should {
    "serialize to Long" in {
      LDATopicId.fromLong(LDATopicId.toLong(LDATopicId(4, 100))) === LDATopicId(4, 100)
      LDATopicId.fromLong(LDATopicId.toLong(LDATopicId(0, 0))) === LDATopicId(0, 0)
      LDATopicId.fromLong(LDATopicId.toLong(LDATopicId(0, 120))) === LDATopicId(0, 120)
      LDATopicId.fromLong(LDATopicId.toLong(LDATopicId(50, 0))) === LDATopicId(50, 0)

      val extreme = (1 << 32) -1
      LDATopicId.fromLong(LDATopicId.toLong(LDATopicId(50, extreme))) === LDATopicId(50, extreme)
    }
  }
}
