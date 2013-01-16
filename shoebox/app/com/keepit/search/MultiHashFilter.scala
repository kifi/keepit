package com.keepit.search

import java.util.Random

object MultiHashFilter {
  def apply(tableSize: Int, numHashFuncs: Int, minHits: Int) = {
    val filter = new Array[Byte](tableSize)
    new MultiHashFilter(tableSize, filter, numHashFuncs, minHits)
  }
}

class MultiHashFilter(tableSize: Int, filter: Array[Byte], numHashFuncs: Int, minHits: Int) {

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
