package com.keepit.search.util

import java.util.Arrays

abstract class LongToLongArrayMap(k: Array[Long], v: Array[Long]) extends Map[Long, Long] {

  override def iterator: Iterator[(Long, Long)] = new Iterator[(Long, Long)] {
    private[this] var index = 0
    override def hasNext(): Boolean = (index < k.length)
    override def next(): (Long, Long) = {
      val ret = (k(index), v(index))
      index += 1
      ret
    }
  }

  override def +[V >: Long](kv: (Long, V)): Map[Long, V] = iterator.toMap + kv

  override def -(k: Long): Map[Long, Long] = iterator.filterNot{ _._1 == k }.toMap

  override def size = k.length
}

object LongToLongArrayMap {

  def fromSorted(k: Array[Long], v: Array[Long]): Map[Long, Long] = {
    new  LongToLongArrayMap(k, v) {

      override def get(key: Long): Option[Long] = {
        val index = Arrays.binarySearch(k, key)
        if (index >= 0) Some(v(index)) else None
      }

      override def contains(key: Long): Boolean = (Arrays.binarySearch(k, key) >= 0)

      override def keySet = LongArraySet.fromSorted(k)
    }
  }

  def from(k: Array[Long], v: Array[Long]): Map[Long, Long] = {
    new LongToLongArrayMap(k, v) {
      private[this] val mapper = ReverseArrayMapper(k, 0.9d, -1)

      override def get(key: Long): Option[Long] = {
        val index = mapper(key)
        if (index >= 0) Some(v(index)) else None
      }

      override def contains(key: Long): Boolean = (mapper(key) >= 0)

      override def keySet = LongArraySet.from(k, mapper)
    }
  }
}
