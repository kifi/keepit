package com.keepit.curator.commanders.email

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.RemoteUserExperimentCommander
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.curator.commanders.RecommendationGenerationCommander
import play.api.libs.concurrent.Execution.Implicits.defaultContext

case class EngagementEmailType(message: String) extends AnyVal

object EngagementEmailTypes {
  val FEED = EngagementEmailType("feed")
}

@Singleton
class EngagementEmailActor @Inject() (
    recommendationGenerationCommander: RecommendationGenerationCommander,
    userExperimentCommander: RemoteUserExperimentCommander,
    engagementFeedSender: EngagementFeedEmailSender,
    protected val airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  import EngagementEmailTypes._

  def receive(): PartialFunction[Any, Unit] = {
    case FEED => engagementFeedSender.send()
  }
}
