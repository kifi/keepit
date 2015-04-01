package com.keepit.search.tracking

import com.keepit.common.store.ObjectStore
import com.keepit.serializer.{ SerializerException, BinaryFormat }

trait MultiHashTracker[O, E] {
  def add(owner: O, events: E*): MultiHashFilter[E]
  def getMultiHashFilter(owner: O): MultiHashFilter[E]
}

trait StoreBackedMultiHashTracker[O, E] extends MultiHashTracker[O, E] {
  val store: ObjectStore[O, MultiHashFilter[E]]
  val builder: MultiHashFilterBuilder[E]
  protected def eventToKey(event: E): Long

  def add(owner: O, events: E*): MultiHashFilter[E] = {
    val filter = getMultiHashFilter(owner)
    events.foreach(e => filter.put(eventToKey(e)))
    store += (owner, filter)
    filter
  }

  def getMultiHashFilter(owner: O): MultiHashFilter[E] = store.syncGet(owner) getOrElse builder.init()
}

trait MultiHashFilterBuilder[E] extends BinaryFormat[MultiHashFilter[E]] {
  val tableSize: Int
  val numHashFuncs: Int
  val minHits: Int
  def init(): MultiHashFilter[E] = build(new Array[Byte](tableSize))
  def build(filter: Array[Byte]): MultiHashFilter[E] = {
    if (filter.length == tableSize) new MultiHashFilter[E](tableSize, filter, numHashFuncs, minHits)
    else throw new SerializerException(s"MultiHashFilter table with ${filter.length} bytes does not match expected size of ${tableSize} bytes")
  }
  protected def writes(prefix: Byte, filter: MultiHashFilter[E]): Array[Byte] = {
    val array = filter.getFilter
    val rv = new Array[Byte](1 + array.length)
    rv(0) = prefix
    System.arraycopy(array, 0, rv, 1, array.length)
    rv
  }
  protected def reads(array: Array[Byte], offset: Int, length: Int): MultiHashFilter[E] = build(array.slice(offset, offset + length))
}
