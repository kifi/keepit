package com.keepit.graph.manager

import org.specs2.mutable.Specification
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.LDA
import com.keepit.model.NormalizedURI
import com.keepit.common.db.SequenceNumber

class CortexSequenceNumberTest extends Specification {
  "CortexSequenceNumber" should {
    "serialize to long" in {
      val version = ModelVersion[LDA](1)
      val seq = SequenceNumber[NormalizedURI](1234567890L)
      val cortexSeq = CortexSequenceNumber(version, seq)
      CortexSequenceNumber.fromLong(CortexSequenceNumber.toLong(cortexSeq)) === cortexSeq

      val maxVersion = ModelVersion[LDA](CortexSequenceNumber.maxVersion.toInt)
      val maxSeq = SequenceNumber[NormalizedURI](CortexSequenceNumber.maxSeq)
      val maxCortexSeq = CortexSequenceNumber(maxVersion, maxSeq)
      CortexSequenceNumber.fromLong(CortexSequenceNumber.toLong(maxCortexSeq)) === maxCortexSeq
    }
  }
}
