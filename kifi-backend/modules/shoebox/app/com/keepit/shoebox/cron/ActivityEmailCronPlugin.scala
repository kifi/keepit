package com.keepit.shoebox.cron

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.emails.ActivityFeedEmailSender
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import us.theatr.akka.quartz.QuartzActor
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.{ Failure, Success }

private[cron] object SendActivityEmail

class ActivityEmailActor @Inject() (
    airbrake: AirbrakeNotifier,
    activityEmailSender: ActivityFeedEmailSender) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case SendActivityEmail =>
      log.info("ActivityEmailActor sending...")
      val doneF = activityEmailSender.apply(None)
      doneF.onComplete {
        case Success(x) => log.info("ActivityEmailActor success")
        case Failure(e) => log.error("ActivityEmailActor failed", e)
      }
  }
}

trait ActivityEmailCronPlugin extends SchedulerPlugin

@Singleton
class ActivityEmailCronPluginImpl @Inject() (
    actor: ActorInstance[ActivityEmailActor],
    quartz: ActorInstance[QuartzActor],
    val scheduling: SchedulingProperties) extends ActivityEmailCronPlugin with Logging {

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    // computes UTC hour for current 9am ET (EDT or EST)
    val nowET = currentDateTime(zones.ET)
    val offsetMillisToUtc = zones.ET.getOffset(nowET)
    val offsetHoursToUtc = offsetMillisToUtc / 1000 / 60 / 60
    val utcHourFor9amEasternTime = 9 + -offsetHoursToUtc

    // ************************************************************************************
    // NOTE: the cron time below is for INTERNAL TESTING ONLY, change it before going live!
    // ************************************************************************************

    // <sec> <min> <hr> <day of mo> <mo> <day of wk> <yr>
    val cronTime = s"0 0 $utcHourFor9amEasternTime ? * *" // 1pm UTC - send every day at 9am ET / 6am PT
    cronTaskOnLeader(quartz, actor.ref, cronTime, SendActivityEmail)
  }
}
