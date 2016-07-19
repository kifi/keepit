package com.keepit.shoebox.cron

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.emails.activity.{ ActivityEmailMessage, ActivityEmailActor }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import us.theatr.akka.quartz.QuartzActor

trait ActivityEmailCronPlugin extends SchedulerPlugin

@Singleton
class ActivityEmailCronPluginImpl @Inject() (
    actor: ActorInstance[ActivityEmailActor],
    quartz: ActorInstance[QuartzActor],
    val scheduling: SchedulingProperties) extends ActivityEmailCronPlugin with Logging {

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() { //what's that??? its already dead imo
    // computes UTC hour for current 9am ET (EDT or EST)
    val nowET = currentDateTime(zones.ET)
    val offsetMillisToUtc = zones.ET.getOffset(nowET)
    val offsetHoursToUtc = offsetMillisToUtc / 1000 / 60 / 60
    val utcHourFor9amEasternTime = 9 + -offsetHoursToUtc

    // <sec> <min> <hr> <day of mo> <mo> <day of wk> <yr>
    //    val cronTime = s"0 0 $utcHourFor9amEasternTime ? * 5" // 1pm UTC - send Thursday at 9am ET / 6am PT
    //    cronTaskOnLeader(quartz, actor.ref, cronTime, ActivityEmailMessage.QueueEmails)
  }
}