package com.keepit.shoebox.cron

import com.google.inject.Inject
import com.keepit.commanders.UserEmailAddressCommander
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import com.keepit.model.{ UserEmailAddressRepo }
import us.theatr.akka.quartz.QuartzActor

import scala.concurrent.ExecutionContext

private[cron] object SendEmailConfirmation

class EmailConfirmationActor @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    userEmailAddressRepo: UserEmailAddressRepo,
    userEmailAddressCommander: UserEmailAddressCommander,
    clock: Clock,
    implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case SendEmailConfirmation =>
      val now = clock.now()
      val to = now.minusDays(1)
      val from = to.minusDays(1)
      val emailsToConfirm = db.readOnlyMaster { implicit s =>
        val allEmails = userEmailAddressRepo.getUnverified(to, from)
        allEmails.filterNot(_.verificationSent)
      }
      log.info(s"sending verification emails to $emailsToConfirm")
      FutureHelpers.sequentialExec(emailsToConfirm) { emailAddress =>
        userEmailAddressCommander.sendVerificationEmail(emailAddress)
      }
  }

}

class EmailConfirmationCron @Inject() (
  actor: ActorInstance[EmailConfirmationActor],
  quartz: ActorInstance[QuartzActor],
  val scheduling: SchedulingProperties)
    extends SchedulerPlugin with Logging {

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() { //kill
    // computes UTC hour for current 9am ET (EDT or EST)
    //    val nowET = currentDateTime(zones.ET)
    //    val offsetMillisToUtc = zones.ET.getOffset(nowET)
    //    val offsetHoursToUtc = offsetMillisToUtc / 1000 / 60 / 60
    //    val utcHourFor9amEasternTime = 9 + -offsetHoursToUtc
    //
    //    // <sec> <min> <hr> <day of mo> <mo> <day of wk> <yr>
    //    val cronTime = s"0 0 $utcHourFor9amEasternTime ? * *" // 1pm UTC - send every Tuesday at 9am EDT / 6am PDT
    //    cronTaskOnLeader(quartz, actor.ref, cronTime, SendEmailConfirmation)
  }
}
