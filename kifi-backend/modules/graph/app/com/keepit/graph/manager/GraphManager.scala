package com.keepit.graph.manager

import com.keepit.graph.model.GraphReader
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.logging.Logging
import com.keepit.inject.AppScoped

trait GraphManager {
  def readOnly[T](f: GraphReader => T): T
  def backup(): Unit
  def update(updates: GraphUpdate*): Unit
  def state: GraphUpdaterState
  def statistics: GraphStatistics
}

trait GraphManagerModule extends ScalaModule with Logging {
  def configure() = {
    bind[GraphUpdater].to[GraphUpdaterImpl]
  }
}

case class GraphManagerPluginModule() extends ScalaModule {
  def configure() = {
    bind[GraphUpdateFetcher].to[GraphUpdateFetcherImpl]
    bind[GraphManagerPlugin].in[AppScoped]
  }
}

class IrrelevantGraphUpdatesException(irrelevantUpdates: Seq[GraphUpdate])
  extends Exception(s"${irrelevantUpdates.length} graph updates have been received out of order: $irrelevantUpdates")
