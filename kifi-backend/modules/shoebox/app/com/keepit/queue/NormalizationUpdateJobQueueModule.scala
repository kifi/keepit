package com.keepit.queue

import com.keepit.common.queue.AmazonSQS
import com.keepit.common.queue.AmazonSQSQueue
import com.keepit.common.queue.SimpleQueueService
import com.keepit.common.queue.SQSMessage
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.keepit.common.logging.Logging

trait NormalizationUpdateJobQueueModule extends ScalaModule

@Singleton
case class ProdNormalizationUpdateJobQueueModule() extends NormalizationUpdateJobQueueModule with Logging {
  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def normalizationUpdateJobQueue(sqs:SimpleQueueService):NormalizationUpdateJobQueue = {
    val qUrl = sqs.getUrl("NTest").getOrElse(throw new IllegalStateException("Cannot retrieve NTest qUrl")) // todo(ray):test->prod mode
    val q = sqs.getByUrl(qUrl).getOrElse(throw new IllegalStateException("Cannot retrieve NTest Q"))
    val awsQ = new AmazonSQSQueue(qUrl, "NTest", sqs.asInstanceOf[AmazonSQS].client) with NormalizationUpdateJobQueue
    log.info(s"[normalizationUpdateTaskQ] sqs=$sqs qUrl=$qUrl q=$q awsQ=$awsQ")
    awsQ
  }

}

@Singleton
case class DevNormalizationUpdateJobQueueModule() extends NormalizationUpdateJobQueueModule with Logging {
  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def normalizationUpdateJobQueue(sqs:SimpleQueueService):NormalizationUpdateJobQueue = {
    val nTestUrl = sqs.create("NTest")
    log.info(s"[normalizationUpdateJobQueue] nTestUrl=$nTestUrl")
    val sQ = sqs.getByUrl(nTestUrl).getOrElse(throw new IllegalStateException())
    log.info(s"[normalizationUpdateJobQueue] sQ=$sQ")
    new NormalizationUpdateJobQueue {
      def queueUrl = sQ.queueUrl
      def receive(): Seq[SQSMessage] = sQ.receive()
      def send(s: String): Unit = sQ.send(s)
      def delete(msgHandle: String): Unit = sQ.delete(msgHandle)
    }
  }

}