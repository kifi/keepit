package com.keepit.graph

import com.keepit.graph.ingestion.{GraphUpdaterState, GraphUpdate}
import com.keepit.graph.model.GraphReader

trait GraphManager {
  def readOnly[T](f: GraphReader => T): T
  def backup(): Unit
  def update(updates: GraphUpdate*): GraphUpdaterState
}
