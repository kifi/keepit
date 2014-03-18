package com.keepit.common.queue

import net.codingwell.scalaguice.ScalaModule
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.regions._
import com.google.inject.{Provides, Singleton}
import com.keepit.common.logging.Logging

import com.kifi.franz.{SimpleSQSClient, QueueName, FakeSQSQueue, SQSQueue}

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
  def richConnectionUpdateQueue(basicAWSCreds:BasicAWSCredentials): SQSQueue[RichConnectionUpdateMessage] = {
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1)
    client.formatted[RichConnectionUpdateMessage](QueueName("rich-connection-update-prod-b"))
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
  def richConnectionUpdateQueue(): SQSQueue[RichConnectionUpdateMessage] = {
    new FakeSQSQueue[RichConnectionUpdateMessage]{}
  }

}
