package com.keepit.search.util.join

class HashJoin(dataBuffer: DataBuffer, numHashBuckets: Int, aggregationContextManager: AggregationContextManager) {

  @inline
  private[this] def hash(id: Long): Int = ((id ^ (id >>> 16)) % numHashBuckets).toInt

  private[this] def buildCumulativeCounts(reader: DataBufferReader): Array[Int] = {
    val count = new Array[Int](numHashBuckets + 1) // the last element will be the total count

    dataBuffer.scan(reader) { reader =>
      // assuming the first datum is ID
      count(hash(reader.nextLong())) += 1
    }

    var i = 0
    while (i < numHashBuckets) { // using while for performance
      count(i + 1) += count(i)
      i += 1
    }
    count
  }

  def execute() {
    val reader = new DataBufferReader

    // build a cumulative count table
    val count = buildCumulativeCounts(reader)

    // group record offsets together by hash values
    val offset = new Array[Int](dataBuffer.size)
    dataBuffer.scan(reader) { reader =>
      // assuming the first datum is ID
      val hashVal = hash(reader.nextLong())
      count(hashVal) -= 1
      offset(count(hashVal)) = reader.recordOffset
    }
    // each element in the count array now points to the beginning of the corresponding group

    // process each group
    var pos = 0
    var i = 0
    while (i < numHashBuckets) { // using while for performance
      val eog = count(i + 1) // end of group
      while (pos < eog) {
        val ptr = offset(pos)
        dataBuffer.set(reader, ptr)
        val id = reader.nextLong()
        val context = aggregationContextManager.get(id)
        context.join(reader) // pushing data to the context

        pos += 1
      }
      // process all joined data in this group and move on to the next group
      aggregationContextManager.flush()
      i += 1
    }
  }
}
