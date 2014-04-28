package com.keepit.cortex

import org.specs2.mutable.Specification

class VersionedSequenceNumberTest extends Specification {
  "VersionedSequenceNumber" should {
    "serialize to long" in {
      VersionedSequenceNumber.fromLong(VersionedSequenceNumber.toLong(VersionedSequenceNumber(1, 1234567890L))) === VersionedSequenceNumber(1, 1234567890L)
      val maxSeq = (1L << 56) - 1
      VersionedSequenceNumber.fromLong(VersionedSequenceNumber.toLong(VersionedSequenceNumber(1, maxSeq))) === VersionedSequenceNumber(1, maxSeq)
    }
  }
}
