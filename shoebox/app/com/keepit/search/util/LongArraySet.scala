package com.keepit.search.util

import java.util.Arrays

abstract class LongArraySet(a: Array[Long]) extends Set[Long] {

  override def iterator = a.iterator

  override def +(elem: Long): Set[Long] = iterator.toSet + elem

  override def -(elem: Long): Set[Long] = iterator.filterNot{ _ == elem }.toSet

  override def size = a.length

  def findIndex(key: Long): Int
}

object LongArraySet {

  def fromSorted(a: Array[Long]): LongArraySet = {
    new LongArraySet(a) {
      override def findIndex(key: Long): Int = Arrays.binarySearch(a, key)
      override def contains(key: Long): Boolean = (Arrays.binarySearch(a, key) >= 0)
    }
  }

  def from(a: Array[Long]): LongArraySet = from(a, ReverseArrayMapper(a, 0.9d, -1L))

  def from(a: Array[Long], mapper: ReverseArrayMapper): LongArraySet = {
    if (a.length != mapper.size) throw new Exception("array size not equal to mapper size")

    new LongArraySet(a) {
      override def findIndex(key: Long): Int = mapper(key)
      override def contains(key: Long): Boolean = (mapper(key) >= 0)
    }
  }
}
