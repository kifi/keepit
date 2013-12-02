package com.keepit.common.mail

import scala.concurrent.duration._

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import play.api.Plugin
import com.keepit.model.{EmailAddressRepo, UserNotifyPreferenceRepo, EmailOptOutRepo}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}

trait MailSenderPlugin extends Plugin {
  def processMail(mail: ElectronicMail)
  def processOutbox()
}

class MailSenderPluginImpl @Inject() (
    actor: ActorInstance[MailSenderActor],
    val schedulingProperties: SchedulingProperties) //only on leader
  extends Logging with MailSenderPlugin with SchedulingPlugin {

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actor.system, 5 seconds, 5 seconds, actor.ref, ProcessOutbox)
  }

  override def processOutbox() { actor.ref ! ProcessOutbox }
  override def processMail(mail: ElectronicMail) { actor.ref ! ProcessMail(mail) }
}

private[mail] case class ProcessOutbox()
private[mail] case class ProcessMail(mailId: ElectronicMail)


private[mail] class MailSenderActor @Inject() (
    db: Database,
    mailRepo: ElectronicMailRepo,
    emailOptOutRepo: EmailOptOutRepo,
    userNotifyPreferenceRepo: UserNotifyPreferenceRepo,
    emailAddressRepo: EmailAddressRepo,
    airbrake: AirbrakeNotifier,
    mailProvider: MailProvider)
  extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case ProcessOutbox =>
      val emailsToSend = db.readOnly { implicit s =>
          mailRepo.outbox() flatMap { email =>
            try {
              Some(mailRepo.get(email))
            } catch {
              case ex: Throwable =>
                airbrake.notify(ex)
                None
            }
        }
      }

      emailsToSend.foreach { mail =>
        self ! ProcessMail(mail)
      }
    case ProcessMail(mail) =>
      log.info(s"Processing email to send: ${mail.id.getOrElse(mail.externalId)}")
      val newMail = takeOutOptOuts(mail)
      if (newMail.state != ElectronicMailStates.OPT_OUT) {
        log.info(s"Sending email: ${newMail.id.getOrElse(newMail.externalId)}")
        mailProvider.sendMail(newMail)
      } else {
        log.info(s"Not sending email due to opt-out: ${newMail.id.getOrElse(newMail.externalId)}")
      }
    case m => throw new UnsupportedActorMessage(m)
  }

  def takeOutOptOuts(mail: ElectronicMail) = { // say that 3 times fast
    val (newTo, newCC) = db.readOnly { implicit session =>
      val newTo = mail.to.filterNot(addressHasOptedOut(_, mail.category))
      val newCC = mail.cc.filterNot(addressHasOptedOut(_, mail.category))
      (newTo, newCC)
    }
    if (newTo.toSet != mail.to.toSet || newCC.toSet != mail.cc.toSet) {
      if (newTo.isEmpty) {
        db.readWrite { implicit session =>
          mailRepo.save(mail.copy(state = ElectronicMailStates.OPT_OUT))
        }
      } else {
        db.readWrite { implicit session =>
          mailRepo.save(mail.copy(to = newTo, cc = newCC))
        }
      }
    } else mail
  }

  def addressHasOptedOut(address: EmailAddressHolder, category: ElectronicMailCategory)(implicit session: RSession) = {
    emailOptOutRepo.hasOptedOut(address, category) || {
      emailAddressRepo.getByAddressOpt(address.address).map(_.userId) match {
        case None => // Email isn't owned by any user, send away!
          false
        case Some(userId) =>
          !userNotifyPreferenceRepo.canNotify(userId, category)
      }
    }
  }
}
