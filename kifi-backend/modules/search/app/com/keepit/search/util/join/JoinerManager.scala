package com.keepit.search.util.join

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

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
  private[this] val pool: ArrayBuffer[Joiner] = new ArrayBuffer[Joiner]() // pool Joiners for reuse
  private[this] var activeCount: Int = 0

  private[this] var table = new mutable.HashMap[Long, Joiner]()

  private def createNewJoiner(): Joiner = {
    if (activeCount >= pool.size) pool += create()
    val joiner = pool(activeCount)
    activeCount += 1
    joiner
  }

  protected def create(): Joiner

  def get(id: Long): Joiner = {
    table.getOrElse(id, {
      val joiner = createNewJoiner().set(id)
      table.put(id, joiner)
      joiner
    })
  }

  def flush(): Unit = {
    var i = 0
    while (i < activeCount) {
      val joiner = pool(i)
      if (joiner != null) {
        joiner.flush()
      }
      i += 1
    }
    table.clear()
    activeCount = 0
  }
}
