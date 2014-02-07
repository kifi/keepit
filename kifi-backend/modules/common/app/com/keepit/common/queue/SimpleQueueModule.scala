package com.keepit.common.queue

import net.codingwell.scalaguice.ScalaModule
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.regions._
import com.google.inject.{Provides, Singleton}
import com.keepit.common.logging.Logging

trait SimpleQueueModule extends ScalaModule

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
  def normalizationUpdateTaskQ(basicAWSCreds:BasicAWSCredentials):NormalizationUpdateJobQueue = {
    val client = new AmazonSQSClient(basicAWSCreds)
    client.setRegion(Region.getRegion(Regions.US_WEST_1))
    val sqs = simpleQueueService(basicAWSCreds)
    val qUrl = sqs.getUrl("NTest").getOrElse(throw new IllegalStateException("Cannot retrieve NTest qUrl"))
    val q = sqs.getByUrl(qUrl).getOrElse(throw new IllegalStateException("Cannot retrieve NTest Q"))
    val awsQ = new AmazonSQSQueue(qUrl, "NTest", client) with NormalizationUpdateJobQueue
    log.info(s"[normalizationUpdateTaskQ] sqs=$sqs qUrl=$qUrl q=$q awsQ=$awsQ")
    awsQ
  }

}

case class DevSimpleQueueModule() extends SimpleQueueModule {
  override def configure(): Unit = {
  }

  val q = new InMemSimpleQueueService
  val nTestUrl = q.create("NTest")
  println(s"nTestUrl=$nTestUrl")
  val sQ = q.getByUrl(nTestUrl).getOrElse(throw new IllegalStateException())
  val nTestQ = sQ.asInstanceOf[NormalizationUpdateJobQueue]

  @Singleton
  @Provides
  def simpleQueueService():SimpleQueueService = q

  @Singleton
  @Provides
  def normalizationUpdateTaskQ():NormalizationUpdateJobQueue = nTestQ

}