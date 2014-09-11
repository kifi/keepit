package com.keepit.curator.commanders.email

import java.util.concurrent.atomic.AtomicBoolean

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

  val isRunning = new AtomicBoolean(false)

  def receive: PartialFunction[Any, Unit] = {
    case Queue => {
      log.info("[Queue] calling FeedDigestEmailSender.send()")
      feedDigestSender.addToQueue().foreach(_ => self ! Send)
    }
    case Send => {
      if (isRunning.compareAndSet(false, true)) {
        log.info("[Send] calling FeedDigestEmailSender.processQueue()")
        feedDigestSender.processQueue().onComplete(_ => isRunning.set(false))
      } else log.info("[Send] skipping; already running")
    }
  }
}
