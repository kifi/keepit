package com.keepit.search.util.join

import org.specs2.mutable._
import scala.util.Random

class BloomFilterTest extends Specification {
  private[this] val rnd = new Random
  private[this] val positiveIds = (0 until 1000).map { _ => rnd.nextInt(Int.MaxValue).toLong }.toSet
  private[this] val negativeIds = {
    var negativeSet = Set.empty[Long]
    while (negativeSet.size < 1000) {
      val id = rnd.nextInt(Int.MaxValue).toLong
      if (!positiveIds.contains(id)) negativeSet += id
    }
    negativeSet
  }

  "BloomFilter" should {
    "detect existence of ids with no false negative" in {
      val buf = new DataBuffer()
      val writer = new DataBufferWriter

      positiveIds.foreach { id => buf.alloc(writer, 1, 8).putLong(id) }

      val bloomFilter = BloomFilter(buf)

      positiveIds.forall(bloomFilter(_)) === true

      val falsePositiveCount = negativeIds.count(bloomFilter(_))

      (falsePositiveCount < negativeIds.size / 10) === true
    }
  }
}
