package com.keepit.curator.queue

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.User
import com.kifi.franz.{ FakeSQSQueue, QueueName, SimpleSQSClient, SQSQueue }
import net.codingwell.scalaguice.ScalaModule
import play.api.libs.json._

case class SendFeedDigestToUserMessage(userId: Id[User])

object SendFeedDigestToUserMessage {
  implicit val format = Format(
    __.read[Id[User]].map(SendFeedDigestToUserMessage.apply),
    new Writes[SendFeedDigestToUserMessage] { def writes(o: SendFeedDigestToUserMessage) = Json.toJson(o.userId) }
  )
}

trait FeedDigestEmailQueueModule extends ScalaModule {
  val queueName: String

  override def configure(): Unit = {}
}

@Singleton
case class ProdFeedDigestEmailQueueModule() extends FeedDigestEmailQueueModule with Logging {
  val queueName = "prod-curator-feed-digest"

  @Singleton
  @Provides
  def sendFeedDigestToUserQueue(basicAWSCreds: BasicAWSCredentials): SQSQueue[SendFeedDigestToUserMessage] = {
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)
    client.formatted[SendFeedDigestToUserMessage](QueueName(queueName))
  }
}

@Singleton
case class DevFeedDigestEmailQueueModule() extends FeedDigestEmailQueueModule with Logging {

  val queueName = "dev-curator-feed-digest"

  @Singleton
  @Provides
  def sendFeedDigestToUserQueue(): SQSQueue[SendFeedDigestToUserMessage] = new FakeSQSQueue[SendFeedDigestToUserMessage] {}
}
