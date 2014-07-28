package com.keepit.search.engine

trait ResultCollector {
  def collect(id: Long, score: Float): Unit
}
