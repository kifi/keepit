package com.keepit.cortex.models.lda

import org.specs2.mutable.Specification
import com.keepit.cortex.core.ModelVersion

class LDATopicIdTest extends Specification {
  "LDATopicId" should {
    "serialize to Long" in {

      var vid = VersionedLDATopicId(4, 100)
      VersionedLDATopicId.getVersion(vid.id) === ModelVersion[DenseLDA](4)
      VersionedLDATopicId.getUnversionedId(vid.id) === LDATopicId(100)

      val extreme = (1 << 32) -1
      vid = VersionedLDATopicId(50, extreme)
      VersionedLDATopicId.getVersion(vid.id) === ModelVersion[DenseLDA](50)
      VersionedLDATopicId.getUnversionedId(vid.id) === LDATopicId(extreme)
    }
  }
}
