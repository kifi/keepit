package com.keepit.search.engine.result

import com.keepit.search.util.join.Joiner

trait ResultCollector[J <: Joiner] {
  def collect(joiner: J): Unit
}
