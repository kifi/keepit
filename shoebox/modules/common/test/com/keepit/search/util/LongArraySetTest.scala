package com.keepit.search.util

import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._
import scala.util.Random

class LongArraySetTest extends Specification {
  val rnd = new Random
  var positive = Set.empty[Long]
  var negative = Set.empty[Long]
  val unsorted = new Array[Long](10)

  var i = 0
  while (positive.size < 10) {
    val v = rnd.nextLong
    if (v != -1L && !positive.contains(v)) {
      unsorted(i) = v
      positive += v
      i += 1
    }
  }
  while (negative.size < 10) {
    val v = rnd.nextLong
    if (v != -1L && !positive.contains(v) && !negative.contains(v)) {
      negative += v
    }
  }

  val sorted: Array[Long] = unsorted.sorted

  "LongArraySet" should {
    "create a set from an sorted array" in {
      val s = LongArraySet.fromSorted(sorted)

      s.size === 10
      positive.forall{ s.contains(_) } === true
      negative.forall{ ! s.contains(_) } === true
      s === positive

      // update
      val plus = negative.head
      (s + plus) === (positive + plus)

      val minus = unsorted(0)
      (s - minus) === (positive - minus)

      // iterator
      s.iterator.toSet === positive
    }

    "create a set from an unsorted array" in {
      val s = LongArraySet.from(unsorted)

      s.size === 10
      positive.forall{ s.contains(_) } === true
      negative.forall{ ! s.contains(_) } === true
      s === positive

      // update
      val plus = negative.head
      (s + plus) === (positive + plus)

      val minus = sorted(0)
      (s - minus) === (positive - minus)

      // iterator
      s.iterator.toSet === positive
    }
  }
}