package com.keepit.heimdal

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.amazonaws.auth.BasicAWSCredentials
import com.kifi.franz.{FakeSQSQueue, SimpleSQSClient, QueueName, SQSQueue}
import play.api.Play._
import com.amazonaws.regions.Regions

trait HeimdalQueueModule extends ScalaModule {
  def configure() = {}
}

case class HeimdalQueueProdModule() extends HeimdalQueueModule {

  import scala.collection.JavaConversions._
  println("\n\t" + (new Exception()).getStackTrace.take(8).mkString("\n\t") + "\n\n")

  @Singleton
  @Provides
  def heimdalEventQueue(basicAWSCreds:BasicAWSCredentials): SQSQueue[Seq[HeimdalEvent]] = {
    val queueName = QueueName(current.configuration.getString("heimdal-events-queue-name").get)
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)
    client.formatted[Seq[HeimdalEvent]](queueName)
  }
}

case class HeimdalQueueDevModule() extends HeimdalQueueModule {

  @Singleton
  @Provides
  def heimdalEventQueue(): SQSQueue[Seq[HeimdalEvent]] = {
    new FakeSQSQueue[Seq[HeimdalEvent]]{}
  }

}
