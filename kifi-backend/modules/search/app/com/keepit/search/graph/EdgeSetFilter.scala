package com.keepit.search.graph

import scala.util.Sorting
import com.keepit.common.db.Id
import com.keepit.search.util.LongArraySet
import org.joda.time.DateTime
import scala.collection.mutable.ArrayBuffer

trait TimeRangeFilter[S, D] {
  def filterByTimeRange(start: Long, end: Long): EdgeSet[S, D]
}
