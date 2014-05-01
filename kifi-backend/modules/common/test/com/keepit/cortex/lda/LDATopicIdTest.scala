package com.keepit.cortex.lda

import org.specs2.mutable.Specification

class LDATopicIdTest extends Specification {
  "LDATopicId" should {
    "serialize to Long" in {

      var vid = VersionedLDATopicId(4, 100)
      VersionedLDATopicId.getVersion(vid.id) === 4
      VersionedLDATopicId.getUnversionedId(vid.id) === 100

      val extreme = (1 << 32) -1
      vid = VersionedLDATopicId(50, extreme)
      VersionedLDATopicId.getVersion(vid.id) === 50
      VersionedLDATopicId.getUnversionedId(vid.id) === extreme
    }
  }
}
