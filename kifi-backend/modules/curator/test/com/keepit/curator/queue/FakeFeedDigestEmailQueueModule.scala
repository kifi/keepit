package com.keepit.curator.queue

import java.util.UUID

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.logging.Logging
import com.kifi.franz.{ SQSMessage, MessageId, FakeSQSQueue, SQSQueue }
import collection.mutable

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.FiniteDuration

class FakeFeedDigestEmailQueue extends FakeSQSQueue[SendFeedDigestToUserMessage] {

  // mutable objects are public so test cases can check the state of the fake queue
  val messages = mutable.Queue[SQSMessage[SendFeedDigestToUserMessage]]()
  val lockedMessages = mutable.Map[MessageId, SQSMessage[SendFeedDigestToUserMessage]]()
  val consumedMessages = mutable.Map[MessageId, SQSMessage[SendFeedDigestToUserMessage]]()

  def unlockAll(): Int = synchronized {
    lockedMessages.keys.map { msgId =>
      messages.enqueue(lockedMessages.remove(msgId).get)
    }.size
  }

  override def nextBatchWithLock(maxBatchSize: Int, lockTimeout: FiniteDuration)(implicit ec: ExecutionContext) = synchronized {
    Future.successful(for (i <- 1 to maxBatchSize if messages.nonEmpty) yield {
      val msg = messages.dequeue()
      lockedMessages(msg.id) = msg
      msg
    })
  }

  override def send(msg: SendFeedDigestToUserMessage): Future[MessageId] = synchronized {
    val msgId = MessageId(UUID.randomUUID().toString)
    val sqsMsg = SQSMessage(id = msgId, body = msg, consume = consume(msgId), attributes = Map.empty)
    messages.enqueue(sqsMsg)
    Future.successful(msgId)
  }

  private def consume(msgId: MessageId)(): Unit = synchronized {
    if (consumedMessages.isDefinedAt(msgId))
      throw new IllegalStateException(s"cannot consume message that's already been consumed $msgId")

    val msgOpt = lockedMessages.remove(msgId)
    msgOpt.foreach { msg =>
      consumedMessages(msgId) = msg
    }
  }

}

case class FakeFeedDigestEmailQueueModule() extends FeedDigestEmailQueueModule with Logging {

  val queueName = "test-curator-feed-digest"

  @Singleton
  @Provides
  def sendFeedDigestToUserQueue(): SQSQueue[SendFeedDigestToUserMessage] = new FakeFeedDigestEmailQueue

}

