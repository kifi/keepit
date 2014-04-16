package com.keepit.graph.manager

import com.keepit.graph.model.GraphReader

trait GraphManager {
  def readOnly[T](f: GraphReader => T): T
  def backup(): Unit
  def update(updates: GraphUpdate*): GraphUpdaterState
}
