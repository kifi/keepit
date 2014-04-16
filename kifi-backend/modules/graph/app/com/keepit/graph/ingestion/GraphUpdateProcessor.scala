package com.keepit.graph.ingestion

import com.keepit.graph.model.GraphWriter
import com.google.inject.Inject

trait GraphUpdateProcessor {
  def apply(update: GraphUpdate)(implicit writer: GraphWriter): Unit
}

class GraphUpdateProcessorImpl @Inject() () extends GraphUpdateProcessor {
  def apply(update: GraphUpdate)(implicit writer: GraphWriter): Unit = update match {
    case _ => ???
  }
}
