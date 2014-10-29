package com.keepit.search.util.join

import scala.collection.mutable

final class AggregationKey(var value: Long) { // a mutable key class
  override def hashCode: Int = { (value ^ (value >>> 32)).asInstanceOf[Int] }
  override def equals(obj: Any): Boolean = {
    obj match {
      case other: AggregationKey => other.value == value
      case _ => false
    }
  }
}

abstract class AggregationContext {
  private[this] var _id: Long = -1L

  final val key = new AggregationKey(-1L)

  def id = _id
  def set(id: Long): AggregationContext = {
    _id = id
    key.value = id
    clear()
    this
  }
  def join(reader: DataBufferReader): Unit
  def flush(): Unit
  def clear(): Unit
}

abstract class AggregationContextManager(initialCapacity: Int) {
  // this class is not thread-safe.

  private[this] var pool: Array[AggregationContext] = new Array[AggregationContext](16) // pool aggregation contexts for reuse
  private[this] var activeCount: Int = 0

  private[this] val table = new mutable.HashMap[AggregationKey, AggregationContext]() {
    // the default value is null when the key is missing (no exception is thrown)
    override def default(key: AggregationKey): AggregationContext = null
  }
  private[this] val searchKey = new AggregationKey(0) // this key instance will be reused over and over

  private def getOrCreateAggregationContext(): AggregationContext = {
    if (activeCount >= pool.length) {
      val arr = new Array[AggregationContext](pool.length * 2)
      System.arraycopy(pool, 0, arr, 0, pool.length)
      pool = arr
    }
    var context = pool(activeCount)
    if (context == null) {
      context = create()
      pool(activeCount) = context
    }
    activeCount += 1
    context
  }

  protected def create(): AggregationContext

  def get(id: Long): AggregationContext = {
    // look up the table. We don't use getOrElse to avoid overhead.
    // getOrElse creates a closure on invocation and internally calls get which creates an Option instance.
    searchKey.value = id
    var context = table(searchKey) // when not found, this returns null as the default value (see the definition above)
    if (context == null) {
      context = getOrCreateAggregationContext().set(id)
      table.put(context.key, context)
    }
    context
  }

  def flush(): Unit = {
    var i = 0
    while (i < activeCount) {
      val context = pool(i)
      if (context == null) throw new IllegalStateException("null context")
      context.flush()
      i += 1
    }
    table.clear()
    activeCount = 0
  }
}
