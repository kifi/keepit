package com.keepit.search.engine.result

import com.keepit.search.util.join.AggregationContext

abstract class ResultCollector[C <: AggregationContext] {
  def collect(context: C): Unit
  def collectDetail(primaryId: Long, secondaryId: Long, visibility: Int, scoreArray: Array[Float]): Unit = {}
}
