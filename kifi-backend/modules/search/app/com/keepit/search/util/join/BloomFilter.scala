package com.keepit.search.util.join

object BloomFilter {
  def apply(dataBuffer: DataBuffer): BloomFilter = {
    val bitMaps = new Array[Long](dataBuffer.size / 4 + 1) // four items per entry (each array entry is a bloom filter)
    dataBuffer.scan(new DataBufferReader) { reader =>
      val id = reader.nextLong()
      val seed = (id ^ (id >>> 16))
      bitMaps((seed % bitMaps.length).toInt) |= genBits(seed)
    }
    new BloomFilter(bitMaps)
  }

  val full: BloomFilter = new BloomFilter(null) {
    override def apply(id: Long): Boolean = true
  }

  @inline private[join] def genBits(value: Long): Long = {
    // unlike conventional BloomFilter, the number of bits turned on is not fixed
    // it is expected that the average number of bits on is close to 8 bits (1/8 of 64 bits)
    // this makes computation very fast
    // this increases the false positive ratio a little, though
    val v1 = value * 6364136223846793005L
    val v2 = (v1 ^ (v1 >>> 16)) * 6364136223846793005L
    val v3 = (v2 ^ (v2 >>> 16)) * 6364136223846793005L

    (v1 & v2 & v3)
  }
}

class BloomFilter(bitMaps: Array[Long]) {
  def apply(id: Long): Boolean = {
    val seed = (id ^ (id >>> 16))
    val bits = BloomFilter.genBits(seed)
    (bits & bitMaps((seed % bitMaps.length).toInt)) == bits
  }
}
