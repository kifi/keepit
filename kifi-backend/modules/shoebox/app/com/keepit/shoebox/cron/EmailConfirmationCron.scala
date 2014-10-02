package com.keepit.shoebox.cron

import com.google.inject.Inject
import com.keepit.commanders.emails.EmailConfirmationSender
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import com.keepit.model.{ UserValueName, UserValueRepo, UserEmailAddressRepo }
import us.theatr.akka.quartz.QuartzActor
import com.keepit.common.concurrent.ExecutionContext.singleThread

private[cron] object SendEmailConfirmation

class EmailConfirmationActor @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    userEmailAddressRepo: UserEmailAddressRepo,
    emailConfirmationSender: EmailConfirmationSender,
    userValueRepo: UserValueRepo,
    clock: Clock) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case SendEmailConfirmation =>
      val now = clock.now()
      val to = now.minusDays(1)
      val from = to.minusDays(1)
      val emailsToConfirm = db.readOnlyMaster { implicit s =>
        val allEmails = userEmailAddressRepo.getUnverified(to, from)
        allEmails.filterNot { email =>
          userValueRepo.getValueStringOpt(email.userId, UserValueName.SENT_EMAIL_CONFIRMATION).exists(_ == true.toString)
        }
      }
      log.info(s"sending verification emails to $emailsToConfirm")
      emailsToConfirm foreach { email =>
        emailConfirmationSender(email).onSuccess {
          case e =>
            db.readWrite { implicit s =>
              userValueRepo.setValue(email.userId, UserValueName.SENT_EMAIL_CONFIRMATION, true)
            }
        }(singleThread)
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
  override def onStart() {
    // computes UTC hour for current 9am ET (EDT or EST)
    val nowET = currentDateTime(zones.ET)
    val offsetMillisToUtc = zones.ET.getOffset(nowET)
    val offsetHoursToUtc = offsetMillisToUtc / 1000 / 60 / 60
    val utcHourFor9amEasternTime = 9 + -offsetHoursToUtc

    // <sec> <min> <hr> <day of mo> <mo> <day of wk> <yr>
    val cronTime = s"0 0 $utcHourFor9amEasternTime ? * *" // 1pm UTC - send every Tuesday at 9am EDT / 6am PDT
    cronTaskOnLeader(quartz, actor.ref, cronTime, SendEmailConfirmation)
  }
}
