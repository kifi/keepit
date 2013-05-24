package com.keepit.search.util

import java.util.Arrays
import com.keepit.common.logging.Logging

abstract class LongArraySet(a: Array[Long]) extends Set[Long] with Logging {

  override def iterator = a.iterator

  override def +(elem: Long): Set[Long] = iterator.toSet + elem

  override def -(elem: Long): Set[Long] = iterator.filterNot{ _ == elem }.toSet

  override def size = a.length

  def key(index: Int): Long = a(index)

  def findIndex(key: Long): Int

  def verify: Boolean
}

object LongArraySet {

  def fromSorted(a: Array[Long]): LongArraySet = {
    new LongArraySet(a) {
      override def findIndex(key: Long): Int = Arrays.binarySearch(a, key)
      override def contains(key: Long): Boolean = (Arrays.binarySearch(a, key) >= 0)
      override def verify: Boolean = {
        if (a.forall(contains)) true else {
          if ((1 until a.length).forall{ i => a(i - 1) < a(i) }) {
            log.error("sorted source: verification failed, source sorted")
          } else {
            log.error("sorted source: verification failed, source not sorted")
          }
          false
        }
      }
    }
  }

  def from(a: Array[Long]): LongArraySet = from(a, ReverseArrayMapper(a, 0.9d, -1L))

  def from(a: Array[Long], mapper: ReverseArrayMapper): LongArraySet = {
    if (a.length != mapper.size) throw new Exception("array size not equal to mapper size")

    new LongArraySet(a) {
      override def findIndex(key: Long): Int = mapper(key)
      override def contains(key: Long): Boolean = (mapper(key) >= 0)
      override def verify: Boolean = {
        if (a.forall(contains)) true else {
          log.error("unsorted source: verification failed")
          false
        }
      }
    }
  }
}
