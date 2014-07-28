package com.keepit.heimdal

import com.keepit.commander.DelightedCommander
import com.keepit.inject.AppScoped

import scala.concurrent.duration._

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

private case object FetchNewDelightedAnswers
private case class ScheduleSurveyForLapsedUsers(skipCount: Int)

class DelightedAnswerReceiverActor @Inject() (
    airbrake: AirbrakeNotifier,
    commander: DelightedCommander) extends FortyTwoActor(airbrake) with Logging {

  def receive = {
    case FetchNewDelightedAnswers =>
      log.info("Checking for new answers")
      commander.fetchNewDelightedAnswers()
    case ScheduleSurveyForLapsedUsers(skipCount) =>
      log.info(s"Scheduling survey for lapsed users (skip count: $skipCount)")
      commander.scheduleSurveyForLapsedUsers(skipCount) map { count =>
        if (count > 0) {
          self ! ScheduleSurveyForLapsedUsers(skipCount + count)
        }
      }
    case m => throw new UnsupportedActorMessage(m)
  }
}

@ImplementedBy(classOf[DelightedPluginImpl])
trait DelightedPlugin extends SchedulerPlugin {
  def fetchNewDelightedAnswers(): Unit
}

@AppScoped
class DelightedPluginImpl @Inject() (
    actor: ActorInstance[DelightedAnswerReceiverActor],
    val scheduling: SchedulingProperties //only on leader
    ) extends DelightedPlugin with Logging {

  override def enabled: Boolean = true
  override def onStart() {
    log.info("Starting DelightedPluginImpl")
    scheduleTaskOnLeader(actor.system, 10 seconds, 15 seconds, actor.ref, FetchNewDelightedAnswers)
    scheduleTaskOnLeader(actor.system, 10 seconds, 1 day, actor.ref, ScheduleSurveyForLapsedUsers(0))
  }

  def fetchNewDelightedAnswers() {
    actor.ref ! FetchNewDelightedAnswers
  }
}
