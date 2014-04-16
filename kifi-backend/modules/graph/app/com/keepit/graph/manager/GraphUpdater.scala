package com.keepit.graph.manager

import com.keepit.graph.model.{GraphWriter}
import com.google.inject.Inject

trait GraphUpdater {
  def apply(update: GraphUpdate)(implicit writer: GraphWriter): Unit
}

class GraphUpdaterImpl @Inject() () extends GraphUpdater {
  def apply(update: GraphUpdate)(implicit writer: GraphWriter): Unit = update match {
    case _ => ???
  }
}
