package com.keepit.search.util.join

import scala.collection.mutable

class HashJoin(dataBuffer: DataBuffer, numHashBuckets: Int, createJoiner: => Joiner) {

  @inline
  private[this] def hash(id: Long): Int = (id % numHashBuckets).toInt

  private[this] def buildCumulativeCounts(reader: DataBufferReader): Array[Int] = {
    val count = new Array[Int](numHashBuckets + 1) // the last element will be the total count

    dataBuffer.scan(reader) { reader =>
      // assuming the first datum is ID
      count(hash(reader.getLong())) += 1
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
      val hashVal = hash(reader.getLong())
      count(hashVal) -= 1
      offset(count(hashVal)) = reader.recordOffset
    }
    // each element in the count array now points to the beginning of the corresponding group

    // process each group
    val joinerMap = new mutable.HashMap[Long, Joiner]()
    val joinerPool = new mutable.Stack[Joiner]() // pool Joiners for reuse
    var pos = 0
    var i = 0
    while (i < numHashBuckets) { // using while for performance
      val eog = count(i + 1) // end of group
      while (pos < eog) {
        val ptr = offset(pos)
        dataBuffer.set(reader, ptr)
        val id = reader.getLong()
        val joiner = getJoiner(id, joinerMap, joinerPool)
        joiner.join(reader) // pushing data to the joiner

        pos += 1
      }
      // process all joined data in this group
      joinerMap.valuesIterator.foreach { joiner =>
        joiner.flush() // telling the active joiner that we are done
        joinerPool.push(joiner)
      }
      // move on to the next group
      joinerMap.clear()
      i += 1
    }
  }

  @inline
  private[this] def getJoiner(id: Long, joinerMap: mutable.HashMap[Long, Joiner], joinerPool: mutable.Stack[Joiner]): Joiner = {
    joinerMap.getOrElse(id,
      if (joinerPool.nonEmpty) {
        val j = joinerPool.pop()
        j.clean()
        joinerMap += ((id, j))
        j.set(id)
      } else {
        val j = createJoiner
        joinerMap += ((id, j))
        j.set(id)
      }
    )
  }
}

trait Joiner {
  var _id: Long = -1
  def set(id: Long): Joiner = { _id = id; clean(); this }
  def id = _id

  def clean(): Unit
  def join(reader: DataBufferReader): Unit
  def flush(): Unit
}
