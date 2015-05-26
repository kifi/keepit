package com.keepit.curator.queue

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.logging.Logging
import com.keepit.common.queue.FakeQueue
import com.kifi.franz.SQSQueue

class FakeFeedDigestEmailQueue extends FakeQueue[SendFeedDigestToUserMessage] {
}

case class FakeFeedDigestEmailQueueModule() extends FeedDigestEmailQueueModule with Logging {

  val queueName = "test-curator-feed-digest"

  @Singleton
  @Provides
  def sendFeedDigestToUserQueue(): SQSQueue[SendFeedDigestToUserMessage] = new FakeFeedDigestEmailQueue

}

