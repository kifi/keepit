package com.keepit.common.queue

import net.codingwell.scalaguice.ScalaModule
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.regions._
import com.google.inject.{Provides, Singleton}
import com.keepit.common.logging.Logging
import com.keepit.inject.EmptyInjector

trait SimpleQueueModule extends ScalaModule

@Singleton
case class ProdSimpleQueueModule() extends SimpleQueueModule with Logging {
  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def simpleQueueService(basicAWSCreds:BasicAWSCredentials):SimpleQueueService = {
    val client = new AmazonSQSClient(basicAWSCreds)
    client.setRegion(Region.getRegion(Regions.US_WEST_1))
    val res = new AmazonSQS(client)
    log.info(s"[simpleQueueService] result: $res")
    res
  }

  @Singleton
  @Provides
  def normalizationUpdateJobQueue(sqs:SimpleQueueService):NormalizationUpdateJobQueue = {
    val qUrl = sqs.getUrl("NTest").getOrElse(throw new IllegalStateException("Cannot retrieve NTest qUrl"))
    val q = sqs.getByUrl(qUrl).getOrElse(throw new IllegalStateException("Cannot retrieve NTest Q"))
    val awsQ = new AmazonSQSQueue(qUrl, "NTest", sqs.asInstanceOf[AmazonSQS].client) with NormalizationUpdateJobQueue
    log.info(s"[normalizationUpdateTaskQ] sqs=$sqs qUrl=$qUrl q=$q awsQ=$awsQ")
    awsQ
  }

}

@Singleton
case class DevSimpleQueueModule() extends SimpleQueueModule with Logging {
  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def simpleQueueService:SimpleQueueService = {
    val sqs = new InMemSimpleQueueService
    log.info(s"[DevSimpleQueueModule.simpleQueueService] created $sqs")
    sqs
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