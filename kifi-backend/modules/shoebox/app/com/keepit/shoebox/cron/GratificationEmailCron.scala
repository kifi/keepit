package com.keepit.shoebox.cron

import com.google.inject.Inject
import com.keepit.commanders.emails.{ GratificationCommander, GratificationEmailSender }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.plugin.{ SchedulingProperties, SchedulerPlugin }
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.shoebox.cron.GratificationEmailMessage.{ SendEvenEmails, SendOddEmails, SendEmails }
import us.theatr.akka.quartz.QuartzActor
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future
import scala.util.{ Failure, Success }

trait GratificationEmailCronPlugin extends SchedulerPlugin

class GratificationEmailCronPluginImpl @Inject() (
    actor: ActorInstance[GratificationEmailActor],
    quartz: ActorInstance[QuartzActor],
    val scheduling: SchedulingProperties) extends GratificationEmailCronPlugin with Logging {

  override def enabled: Boolean = true
  override def onStart() {
    val nowET = currentDateTime(zones.PT)
    val offsetMillisToUtc = zones.PT.getOffset(nowET)
    val offsetHoursToUtc = offsetMillisToUtc / 1000 / 60 / 60
    val utcHourFor9amPacificTime = 9 + -offsetHoursToUtc
    val utcHourFor5pmPacificTime = 17 + -offsetHoursToUtc

    val cronTimeEveryday = s"0 0/30 ${utcHourFor9amPacificTime + 4}-${utcHourFor5pmPacificTime} ? * FRI" // scheduled to send to QA
    cronTaskOnLeader(quartz, actor.ref, cronTimeEveryday, GratificationEmailMessage.SendEmails)

    val cronTimeTues = s"0 0 $utcHourFor9amPacificTime ? * TUE"
    cronTaskOnLeader(quartz, actor.ref, cronTimeTues, GratificationEmailMessage.SendOddEmails)

    val cronTimeMonday = s"0 0 $utcHourFor9amPacificTime ? * MON"
    cronTaskOnLeader(quartz, actor.ref, cronTimeMonday, GratificationEmailMessage.SendEvenEmails)
  }
}

object GratificationEmailMessage {
  object SendEmails
  object SendOddEmails // sends emails to odd-id users, for testing
  object SendEvenEmails // sends emails to even-id users, for testing
}

class GratificationEmailActor @Inject() (
    emailSender: GratificationEmailSender,
    emailCommander: GratificationCommander,
    protected val airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  val testDestinationEmail = EmailAddress("cam@kifi.com") // while in QA, send all emails to Cam

  def receive = {
    case SendEmails =>
      log.info("[GratData] actor message received"); emailCommander.batchSendEmails(filter = Function.const(true), sendTo = Some(testDestinationEmail))
    case SendOddEmails => emailCommander.batchSendEmails(filter = { id => id.id % 2 == 1 }, sendTo = Some(testDestinationEmail))
    case SendEvenEmails => emailCommander.batchSendEmails(filter = { id => id.id % 2 == 0 }, sendTo = Some(testDestinationEmail))
  }
}
