package com.keepit.graph.ingestion

import com.kifi.franz.{SQSMessage, SQSQueue}
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.eliza.ElizaServiceClient

trait GraphUpdateFetcher {
  def nextBatch(maxBatchSize: Int): Future[Seq[SQSMessage[GraphUpdate]]]
  def fetch(currentState: GraphUpdaterState): Unit
}

class GraphUpdateFetcherImpl @Inject() (
  queue: SQSQueue[GraphUpdate],
  shoebox: ShoeboxServiceClient,
  eliza: ElizaServiceClient
) extends GraphUpdateFetcher {
  val lockTimeout: FiniteDuration = ???
  def nextBatch(maxBatchSize: Int): Future[Seq[SQSMessage[GraphUpdate]]] = queue.nextBatchWithLock(maxBatchSize, lockTimeout)
  def fetch(currentState: GraphUpdaterState): Unit = GraphUpdateKind.all.foreach {
    case _ => ???
  }
}
