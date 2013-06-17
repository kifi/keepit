package com.keepit.search

import java.util.Random
import com.keepit.inject._
import play.api.Play.current

object MultiHashFilter {
  def apply[T](tableSize: Int, numHashFuncs: Int, minHits: Int) = {
    val filter = new Array[Byte](tableSize)
    new MultiHashFilter[T](tableSize, filter, numHashFuncs, minHits)
  }

  def emptyFilter[T] = new MultiHashFilter[T](0, Array.empty[Byte], 0, 0) {
    override def put(key: Long) = throw new UnsupportedOperationException
    override def mayContain(key: Long) = false
  }
}

class MultiHashFilter[T](tableSize: Int, filter: Array[Byte], numHashFuncs: Int, minHits: Int) {

  def getFilter = filter

  def put(key: Long) {
    forAllPositionsFor(key){ (pos, fingerprint) =>
      filter(pos) = fingerprint
      true
    }
  }

  def mayContain(key: Long) = {
    var hits = 0
    !forAllPositionsFor(key){ (pos, fingerprint) =>
      if (filter(pos) == fingerprint) hits += 1
      (hits < minHits)
    }
  }

  private[this] def forAllPositionsFor(key: Long)(f: (Int, Byte) => Boolean): Boolean = {
    var v = key & 0x7FFFFFFFFFFFFFFFL
    var i = 0
    val tsize = tableSize.toLong
    while (i < numHashFuncs) {
      v = (v * 0x5DEECE66DL + 0x123456789L) & 0x7FFFFFFFFFFFFFFFL // linear congruential generator
      // pass the position to the given function
      if (f((v % tsize).toInt, ((v / tsize) & 0xFFL).toByte) == false) return false
      i += 1
    }
    true
  }
}
