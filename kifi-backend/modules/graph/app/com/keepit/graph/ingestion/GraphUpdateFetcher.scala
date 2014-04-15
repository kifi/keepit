package com.keepit.graph.ingestion

import com.kifi.franz.{SQSMessage, SQSQueue}
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait GraphUpdateFetcher {
  val lockTimeout: FiniteDuration
  def lockDuration
  def queue: SQSQueue[GraphUpdate]
  def nextBatch(maxBatchSize: Int): Future[Seq[SQSMessage[GraphUpdate]]] = {
    queue.nextBatchWithLock(maxBatchSize, lockTimeout)
  }
  def fetch(currentState: GraphUpdaterState): Unit
}
