package com.keepit.queue

import com.keepit.common.queue.AmazonSQS
import com.keepit.common.queue.AmazonSQSQueue
import com.keepit.common.queue.SimpleQueueService
import com.keepit.common.queue.SimpleQueueMessage
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.keepit.common.logging.Logging

trait NormalizationUpdateJobQueueModule extends ScalaModule {
  def queueName:String
}

@Singleton
case class ProdNormalizationUpdateJobQueueModule() extends NormalizationUpdateJobQueueModule with Logging {

  val queueName = "prod_normalization" // todo(ray): config

  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def normalizationUpdateJobQueue(sqs:SimpleQueueService):NormalizationUpdateJobQueue = {
    val queueUrl = sqs.getUrl(queueName).getOrElse(throw new IllegalStateException(s"Cannot retrieve $queueName")) // todo(ray):test->prod mode
    val q = sqs.getByUrl(queueUrl).getOrElse(throw new IllegalStateException(s"Cannot retrieve $queueName with url: $queueUrl"))
    val awsQ = new AmazonSQSQueue(queueUrl, queueName, sqs.asInstanceOf[AmazonSQS].client) with NormalizationUpdateJobQueue
    log.info(s"[normalizationUpdateTaskQueue] sqs=$sqs qUrl=$queueUrl q=$q awsQ=$awsQ")
    awsQ
  }

}

@Singleton
case class DevNormalizationUpdateJobQueueModule() extends NormalizationUpdateJobQueueModule with Logging {

  val queueName = "dev_normalization" // todo(ray): config

  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def normalizationUpdateJobQueue(sqs:SimpleQueueService):NormalizationUpdateJobQueue = {
    val queueUrl = sqs.create(queueName)
    log.info(s"[normalizationUpdateJobQueue] queueUrl=$queueUrl")
    val q = sqs.getByUrl(queueUrl).getOrElse(throw new IllegalStateException())
    log.info(s"[normalizationUpdateJobQueue] q=$q")
    new NormalizationUpdateJobQueue {
      def name = queueName
      def queueUrl = q.queueUrl
      def receive(): Seq[SimpleQueueMessage] = q.receive()
      def send(s: String): Unit = q.send(s)
      def delete(msgHandle: String): Unit = q.delete(msgHandle)
    }
  }

}