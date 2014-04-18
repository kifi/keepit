package com.keepit.graph.manager

import com.keepit.graph.model.GraphReader
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.logging.Logging
import com.kifi.franz.SQSQueue
import scala.concurrent.Future
import com.keepit.inject.AppScoped
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait GraphManager {
  def readOnly[T](f: GraphReader => T): T
  def backup(): Unit
  def update(updates: GraphUpdate*): GraphUpdaterState
}

trait GraphManagerModule extends ScalaModule with Logging {

  def configure() = {
    bind[GraphUpdater].to[GraphUpdaterImpl]
    bind[GraphUpdateFetcher].to[GraphUpdateFetcherImpl]
    bind[GraphManagerPlugin].in[AppScoped]
  }
}
