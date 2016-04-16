package com.keepit.shoebox.cron

import com.google.inject.Inject
import com.keepit.commanders.{ OrganizationInviteCommander, UserEmailAddressCommander }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import com.keepit.model.UserEmailAddressRepo
import org.joda.time.Duration
import us.theatr.akka.quartz.QuartzActor

import scala.concurrent.ExecutionContext

private[cron] object SendOrganizationInviteReminder

class InviteReminderEmailActor @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    userEmailAddressRepo: UserEmailAddressRepo,
    userEmailAddressCommander: UserEmailAddressCommander,
    organizationInviteCommander: OrganizationInviteCommander,
    clock: Clock,
    implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case SendOrganizationInviteReminder =>
      val minDurationBetweenLastEmail = Duration.standardDays(3)
      val maxRemindersSent = Int.MaxValue // todo(josh) should this be anything?

      log.info(s"sending organization invite reminder emails")
      organizationInviteCommander.sendInviteReminders(minDurationBetweenLastEmail, maxRemindersSent)
  }
}

class InviteReminderEmailCron @Inject() (
  actor: ActorInstance[InviteReminderEmailActor],
  quartz: ActorInstance[QuartzActor],
  val scheduling: SchedulingProperties)
    extends SchedulerPlugin with Logging {

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    // computes UTC hour for current 9am ET (EDT or EST)
    val nowET = currentDateTime(zones.ET)
    val offsetMillisToUtc = zones.ET.getOffset(nowET)
    val offsetHoursToUtc = offsetMillisToUtc / 1000 / 60 / 60
    val utcHourFor9amEasternTime = 9 + -offsetHoursToUtc

    // <sec> <min> <hr> <day of mo> <mo> <day of wk> <yr>
    val cronTime = s"0 0 $utcHourFor9amEasternTime ? * *" // 1pm UTC - send every day at 9am EDT / 6am PDT
    // TODO(josh) uncomment when this is ready to test
    // cronTaskOnLeader(quartz, actor.ref, cronTime, SendOrganizationInviteReminder)
  }
}
