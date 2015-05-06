package com.keepit.common.queue

import java.util.UUID

import com.kifi.franz.{ FakeSQSQueue, MessageId, SQSMessage }

import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.FiniteDuration

class FakeQueue[T] extends FakeSQSQueue[T] {

  // mutable objects are public so test cases can check the state of the fake queue
  val messages = mutable.Queue[SQSMessage[T]]()
  val lockedMessages = mutable.Map[MessageId, SQSMessage[T]]()
  val consumedMessages = mutable.Map[MessageId, SQSMessage[T]]()

  def unlockAll(): Int = synchronized {
    lockedMessages.keys.map { msgId =>
      messages.enqueue(lockedMessages.remove(msgId).get)
    }.size
  }

  override def nextWithLock(lockTimeout: FiniteDuration)(implicit ec: ExecutionContext) = synchronized {
    Future.successful {
      if (messages.nonEmpty) {
        val msg = messages.dequeue()
        lockedMessages(msg.id) = msg
        Some(msg)
      } else None
    }
  }

  override def nextBatchWithLock(maxBatchSize: Int, lockTimeout: FiniteDuration)(implicit ec: ExecutionContext) = synchronized {
    Future.successful(for (i <- 1 to maxBatchSize if messages.nonEmpty) yield {
      val msg = messages.dequeue()
      lockedMessages(msg.id) = msg
      msg
    })
  }

  override def send(msg: T): Future[MessageId] = synchronized {
    val msgId = MessageId(UUID.randomUUID().toString)
    val sqsMsg = SQSMessage(id = msgId, body = msg, consume = consume(msgId), attributes = Map.empty)
    messages.enqueue(sqsMsg)
    Future.successful(msgId)
  }

  private def consume(msgId: MessageId)(): Unit = synchronized {
    if (consumedMessages.isDefinedAt(msgId))
      throw new IllegalStateException(s"cannot consume message that's already been consumed $msgId")

    val msgOpt = lockedMessages.remove(msgId)
    msgOpt.foreach { msg => consumedMessages(msgId) = msg }
  }

  override def toString() = {
    s"FakeQueue(messages=$messages consumedMessages=$consumedMessages lockedMessages=$lockedMessages)"
  }

}
