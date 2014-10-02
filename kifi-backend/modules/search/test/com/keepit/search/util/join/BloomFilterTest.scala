package com.keepit.search.util.join

import org.specs2.mutable._
import scala.util.Random

class BloomFilterTest extends Specification {
  private[this] val rnd = new Random
  private[this] def genPositiveIds(n: Int) = (0 until n).map { _ => rnd.nextInt(Int.MaxValue).toLong }.toSet
  private[this] def genIds(n: Int) = {
    val positiveIds = genPositiveIds(n)
    var negativeIds = Set.empty[Long]
    while (negativeIds.size < n) {
      val id = rnd.nextInt(Int.MaxValue).toLong
      if (!positiveIds.contains(id)) negativeIds += id
    }
    (positiveIds, negativeIds)
  }

  "BloomFilter" should {
    "detect existence of ids with no false negative" in {
      val buf = new DataBuffer()
      val writer = new DataBufferWriter

      val positiveIds = genPositiveIds(1000)
      positiveIds.foreach { id => buf.alloc(writer, 1, 8).putLong(id) }

      val bloomFilter = BloomFilter(buf)

      positiveIds.forall(bloomFilter(_)) === true
    }

    "detect existence of ids with a low false positive ratio" in {
      (0 until 3).exists { _ =>
        val buf = new DataBuffer()
        val writer = new DataBufferWriter

        val (positiveIds, negativeIds) = genIds(10000)
        positiveIds.foreach { id => buf.alloc(writer, 1, 8).putLong(id) }

        val bloomFilter = BloomFilter(buf)
        val falsePositiveCount = negativeIds.count(bloomFilter(_))

        //println(s"\n\t\t falsePositiveCount = $falsePositiveCount (${falsePositiveCount.toDouble / negativeIds.size.toDouble})\n")

        positiveIds.forall(bloomFilter(_)) && (falsePositiveCount < negativeIds.size / 20)
      } === true
    }
  }
}
