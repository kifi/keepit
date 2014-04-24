package com.keepit.graph.manager

import com.kifi.franz.{SQSMessage, SQSQueue}
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.Inject
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.eliza.ElizaServiceClient
import com.keepit.common.akka.SafeFuture
import com.keepit.abook.ABookServiceClient
import com.keepit.common.db.SequenceNumber
import com.keepit.model.User

trait GraphUpdateFetcher {
  def nextBatch(maxBatchSize: Int, lockTimeout: FiniteDuration): Future[Seq[SQSMessage[GraphUpdate]]]
  def fetch(currentState: GraphUpdaterState): Unit
}

class GraphUpdateFetcherImpl @Inject() (
  queue: SQSQueue[GraphUpdate],
  shoebox: ShoeboxServiceClient,
  eliza: ElizaServiceClient,
  abook: ABookServiceClient
) extends GraphUpdateFetcher {
  def nextBatch(maxBatchSize: Int, lockTimeout: FiniteDuration): Future[Seq[SQSMessage[GraphUpdate]]] = new SafeFuture(queue.nextBatchWithLock(maxBatchSize, lockTimeout))
  def fetch(currentState: GraphUpdaterState): Unit = GraphUpdateKind.all.foreach {
    case UserGraphUpdate =>
      val seq = currentState.state(UserGraphUpdate)
      shoebox.sendUserGraphUpdate(SequenceNumber[User](seq))
  }
}
