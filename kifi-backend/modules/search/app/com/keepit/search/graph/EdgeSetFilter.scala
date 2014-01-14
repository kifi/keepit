package com.keepit.search.graph

import scala.util.Sorting
import com.keepit.common.db.Id
import com.keepit.search.util.LongArraySet
import org.joda.time.DateTime
import scala.collection.mutable.ArrayBuffer

trait EdgeSetFilter[S, D] extends EdgeSet[S, D] {
  val inheritedSourceId = sourceId
  def accept(id: Long): Boolean

  def filterEdgeSet(): EdgeSet[S, D] = {
    val subDestId = destIdLongSet.iterator.filter(accept(_)).toArray
    Sorting.quickSort(subDestId)
    new LongSetEdgeSet[S, D] {
      override val sourceId: Id[S] = inheritedSourceId
      override val longArraySet = LongArraySet.fromSorted(subDestId)
    }
  }
}

trait TimeRangeFilter[S, D] extends EdgeSetFilter[S, D] {
//  val start: DateTime
//  val end: DateTime
//  val startLong = start.getMillis
//  val endLong = end.getMillis
//
//  def getTimeStamp(id: Long): Long
//
//  override def accept(id: Long): Boolean = {
//    val t = getTimeStamp(id)
//    startLong <= t && t <= endLong
//  }

  override def accept(id: Long) = true

  def filterByTimeRange(start: Long, end: Long): EdgeSet[S, D]
}

trait LuceneBackedBookmarkTimeRangeFilter[S, D] extends TimeRangeFilter[S, D] with LuceneBackedBookmarkInfoAccessor[S, D] {
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
    Sorting.quickSort(filtered)
    val inheritedSourceId = sourceId
    new LongSetEdgeSet[S, D] {
      override val sourceId: Id[S] = inheritedSourceId
      override val longArraySet = LongArraySet.fromSorted(filtered)
    }
  }
}