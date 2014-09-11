package com.keepit.curator.commanders.email

import com.google.inject.Inject
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import play.api.libs.concurrent.Execution.Implicits.defaultContext

object FeedDigestMessage {
  object Queue
  object Send
}

class EngagementEmailActor @Inject() (
    feedDigestSender: FeedDigestEmailSender,
    protected val airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  import FeedDigestMessage._

  def receive: PartialFunction[Any, Unit] = {
    case Queue => {
      log.info("calling FeedDigestEmailSender.send()")
      feedDigestSender.addToQueue().foreach(_ => self ! Send)
    }
    case Send => {
      log.info("calling FeedDigestEmailSender.processQueue()")
      feedDigestSender.processQueue()
    }
  }

}
