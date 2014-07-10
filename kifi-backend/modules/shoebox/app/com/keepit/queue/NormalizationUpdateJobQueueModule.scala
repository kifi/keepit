package com.keepit.queue

import com.keepit.common.queue._
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.logging.Logging
import com.amazonaws.auth.BasicAWSCredentials
import com.kifi.franz.{ FakeSQSQueue, QueueName, SimpleSQSClient, SQSQueue }
import com.amazonaws.regions.{ Region, Regions }
import com.kifi.franz.QueueName
import com.amazonaws.services.sqs.AmazonSQSClient

trait NormalizationUpdateJobQueueModule extends ScalaModule {
  def queueName: String
}

@Singleton
case class ProdNormalizationUpdateJobQueueModule() extends NormalizationUpdateJobQueueModule with Logging {

  val queueName = "prod_normalization" // todo(ray): config

  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def normalizationUpdateQueue(basicAWSCreds: BasicAWSCredentials): SQSQueue[NormalizationUpdateTask] = {
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)
    client.formatted[NormalizationUpdateTask](QueueName(queueName))
  }

}

@Singleton
case class DevNormalizationUpdateJobQueueModule() extends NormalizationUpdateJobQueueModule with Logging {

  val queueName = "dev_normalization" // todo(ray): config

  override def configure(): Unit = {
  }

  @Singleton
  @Provides
  def normalizationUpdateQueue(): SQSQueue[NormalizationUpdateTask] = {
    new FakeSQSQueue[NormalizationUpdateTask] {}
  }
}