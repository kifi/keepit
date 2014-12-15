package com.keepit.search.index.graph

import scala.collection.mutable.ArrayBuffer
import com.keepit.common.db.Id
import com.keepit.search.util.LongArraySet
import com.keepit.common.logging.Logging
import java.util.Arrays

trait EdgeSetAccessor[S, D] {
  protected val edgeSet: EdgeSet[S, D]
  protected var _destId: Long = -1L
  def destId: Long = _destId
  def seek(id: Id[D]): Boolean = seek(id.id)
  def seek(id: Long): Boolean
}

class SimpleEdgeSetAccessor[S, D](override val edgeSet: EdgeSet[S, D]) extends EdgeSetAccessor[S, D] {
  override def seek(id: Long): Boolean = {
    _destId = id
    edgeSet.destIdLongSet.contains(id)
  }
}

trait LongArrayBasedEdgeInfoAccessor[S, D] extends EdgeSetAccessor[S, D] {
  val longArraySet: LongArraySet
  protected var index: Int = -1

  override def seek(id: Long) = {
    _destId = id
    index = longArraySet.findIndex(id)
    index >= 0
  }
}

class LongArrayBasedEdgeInfoAccessorImpl[S, D](override val edgeSet: EdgeSet[S, D], override val longArraySet: LongArraySet) extends LongArrayBasedEdgeInfoAccessor[S, D]

trait BookmarkInfoAccessor[S, D] extends EdgeSetAccessor[S, D] {
  def createdAt: Long
  def isPublic: Boolean
  def bookmarkId: Long
  def getCreatedAt(id: Long): Long
  def getBookmarkId(id: Long): Long
  def filterByTimeRange(start: Long, end: Long): EdgeSet[S, D]
}

abstract class LuceneBackedBookmarkInfoAccessor[S, D](override val edgeSet: EdgeSet[S, D], override val longArraySet: LongArraySet)
    extends LongArrayBasedEdgeInfoAccessor[S, D] with BookmarkInfoAccessor[S, D] with Logging {

  protected def createdAtByIndex(idx: Int): Long
  protected def isPublicByIndex(idx: Int): Boolean
  protected def bookmarkIdByIndex(idx: Int): Long

  override def createdAt: Long = if (index >= 0) createdAtByIndex(index) else throw new IllegalStateException("accessor is not positioned")
  override def isPublic: Boolean = if (index >= 0) isPublicByIndex(index) else throw new IllegalStateException("accessor is not positioned")
  override def bookmarkId: Long = if (index >= 0) bookmarkIdByIndex(index) else throw new IllegalStateException("accessor is not positioned")

  override def getCreatedAt(id: Long): Long = {
    val idx = longArraySet.findIndex(id)
    if (idx >= 0) {
      createdAtByIndex(idx)
    } else {
      log.error(s"failed in getCreatedAt: src=${edgeSet.sourceId} dest=${id} idx=${idx}")
      if (longArraySet.verify) {
        if (longArraySet.iterator.forall(_ != id)) log.error(s"verified the data structure, but the key does not exists")
      }
      0L //throw new NoSuchElementException(s"failed to find id: ${id}")
    }
  }
  override def getBookmarkId(id: Long): Long = {
    val idx = longArraySet.findIndex(id)
    if (idx >= 0) {
      bookmarkIdByIndex(idx)
    } else {
      log.error(s"failed in getBookmarkId: src=${edgeSet.sourceId} dest=${id} idx=${idx}")
      if (longArraySet.verify) {
        if (longArraySet.iterator.forall(_ != id)) log.error(s"verified the data structure, but the key does not exists")
      }
      -1L //throw new NoSuchElementException(s"failed to find id: ${id}")
    }
  }

  override def filterByTimeRange(startTime: Long, endTime: Long): EdgeSet[S, D] = {
    val buf = new ArrayBuffer[Long]
    var size = longArraySet.size
    var i = 0
    while (i < size) {
      val timestamp = createdAtByIndex(i)
      if (startTime <= timestamp && timestamp <= endTime) buf += longArraySet.key(i)
      i += 1
    }
    val filtered = buf.toArray
    Arrays.sort(filtered)
    val inheritedSourceId = edgeSet.sourceId
    new LongArraySetEdgeSet[S, D] {
      override val sourceId: Id[S] = inheritedSourceId
      override val longArraySet = LongArraySet.fromSorted(filtered)
    }
  }
}
