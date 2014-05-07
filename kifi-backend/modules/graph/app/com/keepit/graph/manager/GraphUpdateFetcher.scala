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
import com.keepit.cortex.CortexServiceClient
import com.keepit.common.logging.Logging

trait GraphUpdateFetcher {
  def nextBatch(maxBatchSize: Int, lockTimeout: FiniteDuration): Future[Seq[SQSMessage[GraphUpdate]]]
  def fetch(currentState: GraphUpdaterState): Unit
}

class GraphUpdateFetcherImpl @Inject() (
  queue: SQSQueue[GraphUpdate],
  shoebox: ShoeboxServiceClient,
  eliza: ElizaServiceClient,
  abook: ABookServiceClient,
  cortex: CortexServiceClient
) extends GraphUpdateFetcher with Logging{
  def nextBatch(maxBatchSize: Int, lockTimeout: FiniteDuration): Future[Seq[SQSMessage[GraphUpdate]]] = {
    log.info(s"Loading next batch of graph updates from the queue: maxBatchSize=$maxBatchSize, lockTimeout=$lockTimeout")
    new SafeFuture(queue.nextBatchWithLock(maxBatchSize, lockTimeout))
  }
  def fetch(currentState: GraphUpdaterState): Unit = {
    implicit val state = currentState
    val queueName = queue.queue
    GraphUpdateKind.all.foreach {
      case UserGraphUpdate => shoebox.sendUserGraphUpdate(queueName, seq(UserGraphUpdate))

      case SocialConnectionGraphUpdate => shoebox.sendSocialConnectionGraphUpdate(queueName, seq(SocialConnectionGraphUpdate))

      case SocialUserInfoGraphUpdate => shoebox.sendSocialUserInfoGraphUpdate(queueName, seq(SocialUserInfoGraphUpdate))

      case UserConnectionGraphUpdate => shoebox.sendUserConnectionGraphUpdate(queueName, seq(UserConnectionGraphUpdate))

      case KeepGraphUpdate => shoebox.sendKeepGraphUpdate(queueName, seq(KeepGraphUpdate))

      case LDAURITopicGraphUpdate => cortex.graphLDAURIFeatureUpdate(queueName, seq(LDAURITopicGraphUpdate))
    }
  }

  private def seq[U <: GraphUpdate](kind: GraphUpdateKind[U])(implicit state: GraphUpdaterState): SequenceNumber[U] = {
    val sequenceNumber = state.getCurrentSequenceNumber(kind)
    log.info(s"Requesting $kind from sequence number $sequenceNumber")
    sequenceNumber
  }
}
