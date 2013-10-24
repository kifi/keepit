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
    airbrake: AirbrakeNotifier,
    mailProvider: MailProvider)
  extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case ProcessOutbox =>
      val emailsToSend = db.readOnly { implicit s =>
          mailRepo.outbox() map { email =>
            try {
              Some(mailRepo.get(email))
            } catch {
              case ex: Throwable =>
                airbrake.notify(AirbrakeError(ex))
                None
            }
        }
      } flatten

      emailsToSend.foreach { mail =>
        self ! ProcessMail(mail)
      }
    case ProcessMail(mail) => mailProvider.sendMail(mail)
    case m => throw new UnsupportedActorMessage(m)
  }
}
