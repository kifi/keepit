package com.keepit.search.util

import java.util.Arrays

abstract class LongArraySet(a: Array[Long]) extends Set[Long] {

  override def iterator = a.iterator

  override def +(elem: Long): Set[Long] = iterator.toSet + elem

  override def -(elem: Long): Set[Long] = iterator.filterNot{ _ == elem }.toSet

  override def size = a.length
}

object LongArraySet {

  def fromSorted(a: Array[Long]): Set[Long] = {
    new LongArraySet(a) {
      override def contains(key: Long): Boolean = (Arrays.binarySearch(a, key) >= 0)
    }
  }

  def from(a: Array[Long]): Set[Long] = from(a, ReverseArrayMapper(a, 0.9d, -1L))

  def from(a: Array[Long], mapper: ReverseArrayMapper): Set[Long] = {
    new LongArraySet(a) {
      override def contains(key: Long): Boolean = (mapper(key) >= 0)
    }
  }
}
