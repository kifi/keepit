package com.keepit.search.util

import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._
import scala.util.Random

class LongToLongArrayMapTest extends Specification {
  val rnd = new Random
  var positive = Map.empty[Long, Long]
  var negative = Set.empty[Long]
  val unsortedKey = new Array[Long](10)
  val unsortedVal = new Array[Long](10)

  var i = 0
  while (positive.size < 10) {
    val v = rnd.nextLong
    if (v != -1L && !positive.isDefinedAt(v)) {
      unsortedKey(i) = v
      unsortedVal(i) = i
      positive += (v -> i)
      i += 1
    }
  }
  while (negative.size < 10) {
    val v = rnd.nextLong
    if (v != -1L && !positive.isDefinedAt(v) && !negative.contains(v)) {
      negative += v
    }
  }

  val sortedKey: Array[Long] = unsortedKey.sorted
  val sortedVal: Array[Long] = unsortedKey.zip(unsortedVal).sortBy(_._1).map(_._2).toArray

  "LongToLongArrayMap" should {
    "create a set from an sorted array" in {
      val s = LongToLongArrayMap.fromSorted(sortedKey, sortedVal)

      s.size === 10
      positive.keySet.forall{ s.isDefinedAt(_) } === true
      negative.forall{ ! s.isDefinedAt(_) } === true
      s === positive

      // update
      val plus = negative.head
      (s + (plus -> 99999L)) === (positive + (plus -> 99999L))

      val minus = unsortedKey(0)
      (s - minus) === (positive - minus)

      // iterator
      s.iterator.toMap === positive

      // key set
      val ks = s.keySet
      ks.size === 10
      positive.keySet.forall{ ks.contains(_) } === true
      negative.forall{ ! ks.contains(_) } === true
      ks === positive.keySet
    }

    "create a set from an unsorted array" in {
      val s = LongToLongArrayMap.from(unsortedKey, unsortedVal)

      s.size === 10
      positive.keySet.forall{ s.isDefinedAt(_) } === true
      negative.forall{ ! s.isDefinedAt(_) } === true
      s === positive

      // update
      val plus = negative.head
      (s + (plus -> 99999L)) === (positive + (plus -> 99999L))

      val minus = unsortedKey(0)
      (s - minus) === (positive - minus)

      // iterator
      s.iterator.toMap === positive

      // key set
      val ks = s.keySet
      ks.size === 10
      positive.keySet.forall{ ks.contains(_) } === true
      negative.forall{ ! ks.contains(_) } === true
      ks === positive.keySet
    }
  }
}