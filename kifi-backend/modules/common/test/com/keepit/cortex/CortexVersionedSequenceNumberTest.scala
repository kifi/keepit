package com.keepit.cortex

import org.specs2.mutable.Specification

class CortexVersionedSequenceNumberTest extends Specification {
  "VersionedSequenceNumber" should {
    "serialize to long" in {
      CortexVersionedSequenceNumber.fromLong(CortexVersionedSequenceNumber.toLong(CortexVersionedSequenceNumber(1, 1234567890L))) === CortexVersionedSequenceNumber(1, 1234567890L)
      val maxSeq = (1L << 56) - 1
      CortexVersionedSequenceNumber.fromLong(CortexVersionedSequenceNumber.toLong(CortexVersionedSequenceNumber(1, maxSeq))) === CortexVersionedSequenceNumber(1, maxSeq)
    }
  }
}
