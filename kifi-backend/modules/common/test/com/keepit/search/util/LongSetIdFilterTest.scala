package com.keepit.search.util

import org.specs2.mutable._

class LongSetIdFilterTest extends Specification {
  "LongSetIdFilter" should {
    "work" in {
      val filter = new LongSetIdFilter()

      val s1 = Set(-1L, 2L, 0L)
      val s2 = Set.empty[Long]
      val s3 = (0 until 1000).map { _.toLong }.toSet

      val t1 = filter.fromSetToBase64(s1)
      val t2 = filter.fromSetToBase64(s2)
      val t3 = filter.fromSetToBase64(s3)

      filter.fromBase64ToSet(t1) === s1
      filter.fromBase64ToSet(t2) === s2
      filter.fromBase64ToSet(t3) === s3

    }
  }

}
