package com.keepit.search.util

import scala.collection.mutable.ArrayStack
import java.lang.Long.bitCount

class ReverseArrayMapper(ids: Array[Long], indexes: Array[Int], sz: Int, bucketStart: Array[Int], bitmaps: Array[Long], chains: Array[Long]) {
  def apply(id: Long): Int = {
    val h = ReverseArrayMapper.hash(id)
    val bucket = (h % sz).toInt
    val start = bucketStart(bucket)
    val bitmap = bitmaps(bucket)
    var mask = (1L << ((h / sz) & 0x3F))
    if ((bitmap & mask) != 0) {
      var c = bitCount(bitmap & (mask - 1L))
      var index = indexes(start + c)
      if (ids(index) == id) {
        return index
      } else {
        val chain = chains(bucket)
        mask = (1L << c)
        if ((chain & mask) != 0) {
          // we have a chain
          val base = bitCount(bitmap)
          while (true) {
            c = base + bitCount(chain & (mask - 1L))
            index = indexes(start + c)
            if (ids(index) == id) return index // found it by traversing the chain. now return.
            else {
              if (c < 64) {
                // traverse the chain
                mask = (1L << c)
                if ((chain & mask) == 0) return -1 // end of the chain. not found.
              } else {
                // run out of chain bits. resort to the linear search
                c = (start + c + 1)
                val end = bucketStart(bucket + 1)
                while (c < end) {
                  index = indexes(c)
                  if (ids(index) == id) return index // found it by the linear search. now return.
                  c += 1
                }
                return -1 // not found
              }
            }
          }
          -1
        } else {
          -1
        }
      }
    } else {
      -1
    }
  }

  def size = ids.length
}

object ReverseArrayMapper {

  def apply(ids: Array[Long], loadFactor: Double = 0.5d, ignore: Long) = {
    val sz = (ids.length.toDouble / loadFactor).toInt / 64 + 1
    val buckets = genBuckets(sz, ids, ignore)
    val (indexes, bitmaps, chains) = genBitmaps(ids, sz, buckets, ignore)
    new ReverseArrayMapper(ids, indexes, sz, buckets, bitmaps, chains)
  }

  private[this] def genBuckets(sz: Int, ids: Array[Long], ignore: Long) = {
    val buckets = new Array[Int](sz + 1)
    var i = 0
    while (i < ids.length) {
      val id = ids(i)
      if (id != ignore) {
        buckets((hash(id) % sz).toInt) += 1
      }
      i += 1
    }
    i = 0
    while (i < sz) {
      buckets(i + 1) += buckets(i)
      i += 1
    }
    buckets
  }

  private[this] def genBitmaps(ids: Array[Long], sz: Int, buckets: Array[Int], ignore: Long) = {
    val indexes = distribute(ids, sz, buckets, ignore)
    val bitmaps = new Array[Long](sz)
    val chains = new Array[Long](sz)
    val slots = new Array[ArrayStack[Int]](64)
    var i = 0
    while(i < 64) {
      slots(i) = new ArrayStack[Int]
      i += 1
    }
    i = 0
    while (i < sz) {
      var start = buckets(i)
      var end = buckets(i + 1)
      val (bitmap, chain) = genBitmap(ids, indexes, start, end, sz, slots)
      bitmaps(i) = bitmap
      chains(i) = chain
      i += 1
    }
    (indexes, bitmaps, chains)
  }

  private[this] def genBitmap(ids: Array[Long], indexes: Array[Int], start: Int, end: Int, sz: Int, slots: Array[ArrayStack[Int]]) = {
    val l = end - start
    var bitmap = 0L
    var chain = 0L
    var c = 0

    // bucketing
    while (c < l) {
      val index = indexes(start + c)
      val id = ids(index)
      val i = ((hash(id) / sz) & 0x3FL).toInt
      slots(i) += index
      c += 1
    }
    // first level
    c = 0
    var i = 0
    while (i < 64) {
      val slot = slots(i)
      if (!slot.isEmpty) {
        bitmap |= (1L << i)
        indexes(start + c) = slot.pop
        if (!slot.isEmpty) {
          chain |= (1L << c)
        }
        c += 1
      }
      i += 1
    }
    // chains
    i = 0
    while (c < l) {
      val slot = slots(i % 64)
      if (!slot.isEmpty) {
        indexes(start + c) = slot.pop
        if (!slot.isEmpty) {
          if (c < 64) chain |= (1L << c)
        }
        c += 1
      }
      i += 1
    }
    (bitmap, chain)
  }

  private[this] def distribute(ids: Array[Long], sz: Int, buckets: Array[Int], ignore: Long) = {
    val indexes = new Array[Int](ids.length)
    var i = 0
    while (i < ids.length) {
      val id = ids(i)
      if (id != ignore) {
        val bucket = (hash(id) % sz).toInt
        buckets(bucket) -= 1
        indexes(buckets(bucket)) = i
      }
      i += 1
    }
    indexes
  }

  private[this] val MIXER = 2147482951L; // a prime number
  def hash(v: Long) = (((v >> 32) + v) * MIXER) >>> 1
}
