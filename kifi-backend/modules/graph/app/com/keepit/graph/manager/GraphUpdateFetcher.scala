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
  def nextBatch(maxBatchSize: Int, lockTimeout: FiniteDuration): Future[Seq[SQSMessage[GraphUpdate]]] = new SafeFuture(queue.nextBatchWithLock(maxBatchSize, lockTimeout))
  def fetch(currentState: GraphUpdaterState): Unit = {
    implicit val state = currentState
    GraphUpdateKind.all.foreach {
      case UserGraphUpdate => shoebox.sendUserGraphUpdate(queue.queue, seq(UserGraphUpdate))

      case SocialConnectionGraphUpdate => shoebox.sendSocialConnectionGraphUpdate(queue.queue, seq(SocialConnectionGraphUpdate))

      case SocialUserInfoGraphUpdate => shoebox.sendSocialUserInfoGraphUpdate(queue.queue, seq(SocialUserInfoGraphUpdate))

      case UserConnectionGraphUpdate => shoebox.sendUserConnectionGraphUpdate(queue.queue, seq(UserConnectionGraphUpdate))

      case KeepGraphUpdate => shoebox.sendKeepGraphUpdate(queue.queue, seq(KeepGraphUpdate))

      case LDAURITopicGraphUpdate => cortex.graphLDAURIFeatureUpdate(queue.queue, seq(LDAURITopicGraphUpdate))
    }
  }

  private def seq[U <: GraphUpdate](kind: GraphUpdateKind[U])(implicit state: GraphUpdaterState): SequenceNumber[U] = {
    val sequenceNumber = state.getCurrentSequenceNumber(kind)
    log.info(s"Fetching $kind from sequence number $sequenceNumber")
    sequenceNumber
  }
}
