package com.keepit.search.tracking

object MultiHashFilter {
  def apply[T](tableSize: Int, numHashFuncs: Int, minHits: Int) = {
    val filter: Array[Byte] = try {
      new Array[Byte](tableSize)
    } catch {
      case e: NegativeArraySizeException =>
        throw new Exception(s"with table size $tableSize", e)
    }
    new MultiHashFilter[T](tableSize, filter, numHashFuncs, minHits)
  }

  def emptyFilter[T] = new MultiHashFilter[T](0, Array.empty[Byte], 0, 0) {
    override def put(key: Long) = throw new UnsupportedOperationException
    override def mayContain(key: Long, minFingerprintMatches: Int = 0) = false
  }
}

class MultiHashFilter[T](tableSize: Int, filter: Array[Byte], numHashFuncs: Int, minHits: Int) {

  def getFilter = filter

  @inline private[this] def init(k: Long): Long = k & 0x7FFFFFFFFFFFFFFFL
  @inline private[this] def next(v: Long): Long = (v * 0x5DEECE66DL + 0x123456789L) & 0x7FFFFFFFFFFFFFFFL // linear congruential generator
  @inline private[this] def pos(v: Long, tsize: Long): Int = (v % tsize).toInt
  @inline private[this] def fingerprint(v: Long, tsize: Long): Byte = ((v / tsize) & 0xFFL).toByte

  def put(key: Long): Unit = {
    var v = init(key)
    var i = 0
    val tsize = tableSize.toLong
    while (i < numHashFuncs) {
      v = next(v)
      filter(pos(v, tsize)) = fingerprint(v, tsize)

      i += 1
    }
  }

  def mayContain(key: Long, minFingerprintMatches: Int = minHits): Boolean = {
    var matchCount = 0
    var v = init(key)
    var i = 0
    val tsize = tableSize.toLong
    while (i < numHashFuncs) {
      v = next(v)
      if (filter(pos(v, tsize)) == fingerprint(v, tsize)) {
        matchCount += 1
        if (matchCount >= minFingerprintMatches) return true
      }
      i += 1
    }
    false
  }
}
