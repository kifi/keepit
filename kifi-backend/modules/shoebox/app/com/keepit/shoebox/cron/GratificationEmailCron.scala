package com.keepit.shoebox.cron

import com.google.inject.Inject
import com.keepit.commanders.emails.{ GratificationCommander, GratificationEmailSender }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.plugin.{ SchedulingProperties, SchedulerPlugin }
import com.keepit.common.time._
import com.keepit.shoebox.cron.GratificationEmailMessage.SendEmails
import us.theatr.akka.quartz.QuartzActor
import play.api.libs.concurrent.Execution.Implicits._

trait GratificationEmailCronPlugin extends SchedulerPlugin

class GratificationEmailCronPluginImpl @Inject() (
    actor: ActorInstance[GratificationEmailActor],
    quartz: ActorInstance[QuartzActor],
    val scheduling: SchedulingProperties) extends GratificationEmailCronPlugin with Logging {

  override def enabled: Boolean = true
  override def onStart() {
    val nowET = currentDateTime(zones.ET)
    val offsetMillisToUtc = zones.ET.getOffset(nowET)
    val offsetHoursToUtc = offsetMillisToUtc / 1000 / 60 / 60
    val utcHourFor9amEasternTime = 9 + -offsetHoursToUtc

    val cronTime = s"0 0 $utcHourFor9amEasternTime ? * 5" // 1pm UTC - send Thursday at 9am ET / 6am PT
    cronTaskOnLeader(quartz, actor.ref, cronTime, GratificationEmailMessage.SendEmails)
  }
}

class GratificationEmailActor @Inject() (
    emailSender: GratificationEmailSender,
    emailCommander: GratificationCommander,
    protected val airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  val testDestinationEmail = EmailAddress("cam@kifi.com") // while testing in production, send all scheduled emails to Cam

  def receive = {
    case SendEmails =>
      emailCommander.usersToSendEmailTo.map { ids => ids.foreach { id => emailSender.sendToUser(id, Some(testDestinationEmail)) } }
  }
}

object GratificationEmailMessage {
  object SendEmails
}
