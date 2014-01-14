package com.keepit.search.graph

import scala.collection.mutable.ArrayBuffer
import com.keepit.common.db.Id
import scala.util.Sorting
import com.keepit.search.util.LongArraySet
import com.keepit.common.logging.Logging

//trait BookmarkVisitor[S, Bookmark] extends TimeRangeFilter[S, Bookmark] with BookmarkInfoAccessor[S, Bookmark] {
//  override def getTimeStamp(id: Long): Long = getCreatedAt(id)
//}
//
//trait LuceneBackedBookmarkVisitor[S, Bookmark] extends TimeRangeFilter[S, Bookmark] with LuceneBackedBookmarkInfoAccessor[S, Bookmark] {
//  override def getTimeStamp(id: Long) = getCreatedAt(id)
//  override def filterEdgeSet(): EdgeSet[S, Bookmark] = {
//    val buf = new ArrayBuffer[Long]
//    var size = longArraySet.size
//    var i = 0
//    while (i < size) {
//      val timestamp = createdAtByIndex(i)
//      if (startLong <= timestamp && timestamp <= endLong) buf += longArraySet.key(i)
//      i += 1
//    }
//    val filtered = buf.toArray
//    Sorting.quickSort(filtered)
//    val inheritedSourceId = sourceId
//    new LongSetEdgeSet[S, Bookmark] {
//      override val sourceId: Id[S] = inheritedSourceId
//      override val longArraySet = LongArraySet.fromSorted(filtered)
//    }
//  }
//}

trait EdgeSetAccessor[S, D] extends EdgeSet[S, D] {
  override def accessor: EdgeSetAccessor[S, D] = this
  protected var _destId: Long = -1L

  def seek(id: Id[D]): Boolean = seek(id.id)
  def seek(id: Long): Boolean = {
    _destId = id
    destIdLongSet.contains(id)
  }

  def destId: Long = _destId
}

trait LongArrayBasedEdgeInfoAccessor[S, D] extends EdgeSetAccessor[S, D] {
  protected var index: Int = -1
  protected val longArraySet: LongArraySet

  override def seek(id: Long) = {
    _destId = id
    index = longArraySet.findIndex(id)
    index >= 0
  }
}

trait BookmarkInfoAccessor[S, D] extends EdgeSetAccessor[S, D]{
  def createdAt: Long = throw new UnsupportedOperationException
  def isPublic: Boolean = throw new UnsupportedOperationException
  def isPrivate: Boolean = throw new UnsupportedOperationException
  def bookmarkId: Long = throw new UnsupportedOperationException

  def getCreatedAt(id: Long): Long = throw new UnsupportedOperationException
  def getBookmarkId(id: Long): Long = throw new UnsupportedOperationException
}

trait LuceneBackedBookmarkInfoAccessor[S, D]
  extends BookmarkInfoAccessor[S, D] with LongArrayBasedEdgeInfoAccessor[S, D] with Logging{

  protected def createdAtByIndex(idx: Int): Long
  protected def isPublicByIndex(idx: Int): Boolean
  protected def bookmarkIdByIndex(idx: Int): Long = ???

  override def createdAt: Long = if (index >= 0) createdAtByIndex(index) else throw new IllegalStateException("accessor is not positioned")
  override def isPublic: Boolean = if (index >= 0) isPublicByIndex(index) else throw new IllegalStateException("accessor is not positioned")
  override def bookmarkId: Long = if (index >= 0) bookmarkIdByIndex(index) else throw new IllegalStateException("accessor is not positioned")

  override def getCreatedAt(id: Long): Long = {
    val idx = longArraySet.findIndex(id)
    if (idx >= 0) {
      createdAtByIndex(idx)
    } else {
      log.error(s"failed in getCreatedAt: src=${} dest=${id} idx=${idx}")
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
      log.error(s"failed in getBookmarkId: src=${} dest=${id} idx=${idx}")
      if (longArraySet.verify) {
        if (longArraySet.iterator.forall(_ != id)) log.error(s"verified the data structure, but the key does not exists")
      }
      -1L //throw new NoSuchElementException(s"failed to find id: ${id}")
    }
  }
}