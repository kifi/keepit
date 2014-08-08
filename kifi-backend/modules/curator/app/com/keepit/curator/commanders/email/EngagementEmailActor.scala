package com.keepit.curator.commanders.email

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.curator.commanders.RecommendationGenerationCommander
import play.api.libs.concurrent.Execution.Implicits.defaultContext

object EngagementEmailTypes {
  object FEED
}

class EngagementEmailActor @Inject() (
    engagementFeedSender: EngagementFeedEmailSender,
    protected val airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  import EngagementEmailTypes._

  def receive(): PartialFunction[Any, Unit] = {
    case FEED => {
      log.info("calling EngagementFeedEmailSender.send() from " + getClass.getName)
      engagementFeedSender.send()
    }
  }
}
