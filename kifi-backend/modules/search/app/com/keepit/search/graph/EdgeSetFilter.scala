package com.keepit.search.graph

import scala.util.Sorting
import com.keepit.common.db.Id
import com.keepit.search.util.LongArraySet
import org.joda.time.DateTime
import scala.collection.mutable.ArrayBuffer

trait TimeRangeFilter[S, D] {
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
