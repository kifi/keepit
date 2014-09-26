package com.keepit.search.util.join

object BloomFilter {
  private[this] val ITEMS_PER_ENTRY = 2
  def apply(dataBuffer: DataBuffer): BloomFilter = {
    val bitMaps = new Array[Int]((dataBuffer.size + ITEMS_PER_ENTRY - 1) / ITEMS_PER_ENTRY) // 4 items per array entry, each array entry is a bloom filter
    dataBuffer.scan(new DataBufferReader) { reader =>
      val id = reader.nextLong()
      bitMaps((id % bitMaps.length).toInt) |= genBits(id)
    }
    new BloomFilter(bitMaps)
  }

  val full: BloomFilter = new BloomFilter(null) {
    override def apply(id: Long): Boolean = true
  }

  private[join] def genBits(id: Long): Int = {
    // unlike conventional BloomFilter, the number of bits turned on is not fixed
    // it is expected that the average number of bits on is close to 8 bits (1/4 of 32 bits)
    // this makes computation very fast
    // this increases the false positive ratio a little, though
    val v1 = (id * 0x5DEECE66DL + 0x123456789L)
    val v2 = (v1 * 0x5DEECE66DL + 0x123456789L)

    ((v1 & v2) >> 16).toInt
  }
}

class BloomFilter(bitMaps: Array[Int]) {
  def apply(id: Long): Boolean = {
    val bits = BloomFilter.genBits(id)
    (bits & bitMaps((id % bitMaps.length).toInt)) == bits
  }
}
