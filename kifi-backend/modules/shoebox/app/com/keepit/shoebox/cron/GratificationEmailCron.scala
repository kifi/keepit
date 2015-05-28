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
import com.keepit.shoebox.cron.GratificationEmailMessage.{ SendEvenEmails, SendOddEmails, SendEmails }
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
    val utcHourForNoonEasternTime = 12 + -offsetHoursToUtc
    val utcHourFor8pmEasternTime = 8 + -offsetHoursToUtc

    val cronTimeEveryday = s"0 0 ${utcHourForNoonEasternTime + 2} ? * *" // scheduled to send to QA
    cronTaskOnLeader(quartz, actor.ref, cronTimeEveryday, GratificationEmailMessage.SendEmails)

    val cronTimeFriday = s"0 0 $utcHourForNoonEasternTime ? * FRI"
    cronTaskOnLeader(quartz, actor.ref, cronTimeEveryday, GratificationEmailMessage.SendOddEmails)

    val cronTimeMonday = s"0 0 $utcHourForNoonEasternTime ? * MON"
    cronTaskOnLeader(quartz, actor.ref, cronTimeEveryday, GratificationEmailMessage.SendEvenEmails)
  }
}

class GratificationEmailActor @Inject() (
    emailSender: GratificationEmailSender,
    emailCommander: GratificationCommander,
    protected val airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  val testDestinationEmail = EmailAddress("qa.test@kifi.com") // while in QA, send all emails to Jo

  def receive = {
    case SendEmails =>
      emailCommander.usersToSendEmailTo.map { ids => ids.foreach { id => emailSender.sendToUser(id, Some(testDestinationEmail)) } } // remove Some(...) upon deployment
    case SendOddEmails =>
      emailCommander.usersToSendEmailTo.map { ids => ids.filter { id => id.id % 2 == 1 }.foreach { id => emailSender.sendToUser(id, None) } }
    case SendEvenEmails =>
      emailCommander.usersToSendEmailTo.map { ids => ids.filter { id => id.id % 2 == 0 }.foreach { id => emailSender.sendToUser(id, None) } }
  }
}

object GratificationEmailMessage {
  object SendEmails

  object SendOddEmails // sends emails to odd-id users, for testing
  object SendEvenEmails // sends emails to even-id users, for testing
}
