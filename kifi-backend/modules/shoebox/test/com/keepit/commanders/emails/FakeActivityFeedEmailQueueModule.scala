package com.keepit.commanders.emails

import com.google.inject.{ Provides, Singleton }
import com.keepit.commanders.emails.activity.{ ActivityEmailQueueModule, SendActivityEmailToUserMessage }
import com.keepit.common.logging.Logging
import com.keepit.common.queue.FakeQueue
import com.kifi.franz.SQSQueue

class FakeActivityEmailQueue extends FakeQueue[SendActivityEmailToUserMessage] {
}

case class FakeActivityFeedEmailQueueModule() extends ActivityEmailQueueModule with Logging {

  // this SQS queue doesn't actually exist
  val queueName = "test-activity-feed-email"

  @Singleton
  @Provides
  def activityEmailQueue(): SQSQueue[SendActivityEmailToUserMessage] = new FakeActivityEmailQueue

}

