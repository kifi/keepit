package com.keepit.cortex

case class VersionedSequenceNumber(version: Int, seq: Long)

object VersionedSequenceNumber {
  // top 8 bits reserved for version
  val maxSeq = (1L << 56) - 1

  def toLong(vseq: VersionedSequenceNumber) = {
    assume(vseq.seq <= maxSeq)
    vseq.version.toLong << 56 | vseq.seq
  }

  def fromLong(x: Long): VersionedSequenceNumber = {
    val version = x >> 56
    val seq = ~(0xFFL << 56) & x
    VersionedSequenceNumber(version.toInt, seq)
  }
}
