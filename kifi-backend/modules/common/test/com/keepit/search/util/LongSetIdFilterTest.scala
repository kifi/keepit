package com.keepit.search.util

import com.keepit.common.util.LongSetIdFilter
import org.specs2.mutable._

class LongSetIdFilterTest extends Specification {
  "LongSetIdFilter" should {
    "work" in {
      val s1 = Set(-1L, 2L, 0L)
      val s2 = Set.empty[Long]
      val s3 = (0 until 1000).map { _.toLong }.toSet

      val t1 = LongSetIdFilter.fromSetToBase64(s1)
      val t2 = LongSetIdFilter.fromSetToBase64(s2)
      val t3 = LongSetIdFilter.fromSetToBase64(s3)

      LongSetIdFilter.fromBase64ToSet(t1) === s1
      LongSetIdFilter.fromBase64ToSet(t2) === s2
      LongSetIdFilter.fromBase64ToSet(t3) === s3

      LongSetIdFilter.fromBase64ToSet("") === Set()

    }
  }

}
