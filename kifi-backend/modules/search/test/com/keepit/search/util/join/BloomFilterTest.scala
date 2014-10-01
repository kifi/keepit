package com.keepit.search.util.join

import org.specs2.mutable._
import scala.util.Random

class BloomFilterTest extends Specification {
  private[this] val rnd = new Random
  private[this] def genPositiveIds = (0 until 1000).map { _ => rnd.nextInt(Int.MaxValue).toLong }.toSet
  private[this] def genIds = {
    val positiveIds = genPositiveIds
    var negativeIds = Set.empty[Long]
    while (negativeIds.size < 1000) {
      val id = rnd.nextInt(Int.MaxValue).toLong
      if (!positiveIds.contains(id)) negativeIds += id
    }
    (positiveIds, negativeIds)
  }

  "BloomFilter" should {
    "detect existence of ids with no false negative" in {
      val buf = new DataBuffer()
      val writer = new DataBufferWriter

      val positiveIds = genPositiveIds
      positiveIds.foreach { id => buf.alloc(writer, 1, 8).putLong(id) }

      val bloomFilter = BloomFilter(buf)

      positiveIds.forall(bloomFilter(_)) === true
    }

    "detect existence of ids with a low false positive ratio" in {
      (0 until 3).exists { _ =>
        val buf = new DataBuffer()
        val writer = new DataBufferWriter

        val (positiveIds, negativeIds) = genIds
        positiveIds.foreach { id => buf.alloc(writer, 1, 8).putLong(id) }

        val bloomFilter = BloomFilter(buf)
        val falsePositiveCount = negativeIds.count(bloomFilter(_))

        //println(s"\n\t\t falsePositiveCount = $falsePositiveCount (${falsePositiveCount.toDouble / negativeIds.size.toDouble})\n")

        positiveIds.forall(bloomFilter(_)) && (falsePositiveCount < negativeIds.size / 20)
      } === true
    }
  }
}
