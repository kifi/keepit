package com.keepit.search.util.join

import scala.collection.mutable

final class JoinerKey(var value: Long) { // a mutable key class
  override def hashCode: Int = { (value ^ (value >>> 32)).asInstanceOf[Int] }
  override def equals(obj: Any): Boolean = {
    obj match {
      case other: JoinerKey => other.value == value
      case _ => false
    }
  }
}

abstract class Joiner {
  private[this] var _id: Long = -1L

  final val key = new JoinerKey(-1L)

  def id = _id
  def set(id: Long): Joiner = {
    _id = id
    key.value = id
    clear()
    this
  }
  def join(reader: DataBufferReader): Unit
  def flush(): Unit
  def clear(): Unit
}

abstract class JoinerManager(initialCapacity: Int) {
  // this class is not thread-safe.

  private[this] var pool: Array[Joiner] = new Array[Joiner](16) // pool Joiners for reuse
  private[this] var activeCount: Int = 0

  private[this] val table = new mutable.HashMap[JoinerKey, Joiner]() {
    // the default value is null when the key is missing (no exception is thrown)
    override def default(key: JoinerKey): Joiner = null
  }
  private[this] val searchKey = new JoinerKey(0) // this key instance will be reused over and over

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
    // look up the table. We don't use getOrElse to avoid overhead.
    // getOrElse creates a closure on invocation and internally calls get which creates an Option instance.
    searchKey.value = id
    var joiner = table(searchKey) // when not found, this returns null as the default value (see the definition above)
    if (joiner == null) {
      joiner = getOrCreateJoiner().set(id)
      table.put(joiner.key, joiner)
    }
    joiner
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
