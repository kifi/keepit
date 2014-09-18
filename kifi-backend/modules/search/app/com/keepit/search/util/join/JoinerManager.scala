package com.keepit.search.util.join

import scala.collection.mutable

abstract class Joiner {
  private[this] var _id: Long = -1

  def id = _id
  def set(id: Long): Joiner = { _id = id; clear(); this }
  def join(reader: DataBufferReader): Unit
  def flush(): Unit
  def clear(): Unit
}

abstract class JoinerManager(initialCapacity: Int) {

  private[this] val overflowSize = 2
  private[this] var pool: Array[Joiner] = new Array[Joiner](16) // pool Joiners for reuse
  private[this] var activeCount: Int = 0
  private[this] val table = new mutable.HashMap[Long, Joiner]()

  private def getOrCreateJoiner(): Joiner = {
    if (activeCount >= pool.length) {
      val arr = new Array[Joiner](pool.length * 2)
      System.arraycopy(pool, 0, arr, 0, pool.length)
      pool = arr
    }
    var joiner = pool(activeCount)
    if (joiner == null) {
      joiner = create()
      pool(activeCount) = joiner
    }
    activeCount += 1
    joiner
  }

  protected def create(): Joiner

  def get(id: Long): Joiner = {
    table.getOrElse(id, {
      val joiner = getOrCreateJoiner().set(id)
      table.put(id, joiner)
      joiner
    })
  }

  def flush(): Unit = {
    var i = 0
    while (i < activeCount) {
      val joiner = pool(i)
      if (joiner == null) throw new IllegalStateException("null joiner")
      joiner.flush()
      i += 1
    }
    table.clear()
    activeCount = 0
  }
}
