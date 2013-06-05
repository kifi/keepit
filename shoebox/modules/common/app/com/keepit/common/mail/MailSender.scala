package com.keepit.common.mail

import scala.concurrent.duration._

import com.google.inject.Inject
import com.keepit.common.actor.ActorFactory
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import play.api.Plugin

trait MailSenderPlugin extends Plugin {
  def processMail(mail: ElectronicMail)
  def processOutbox()
}

class MailSenderPluginImpl @Inject() (
    actorFactory: ActorFactory[MailSenderActor],
    val schedulingProperties: SchedulingProperties)
  extends Logging with MailSenderPlugin with SchedulingPlugin {

  private lazy val actor = actorFactory.get()

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actorFactory.system, 5 seconds, 5 seconds, actor, ProcessOutbox)
  }

  override def processOutbox() { actor ! ProcessOutbox }
  override def processMail(mail: ElectronicMail) { actor ! ProcessMail(mail) }
}

private[mail] case class ProcessOutbox()
private[mail] case class ProcessMail(mailId: ElectronicMail)


private[mail] class MailSenderActor @Inject() (
    db: Database,
    mailRepo: ElectronicMailRepo,
    healthcheckPlugin: HealthcheckPlugin,
    mailProvider: MailProvider)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case ProcessOutbox =>
      db.readOnly { implicit s =>
        mailRepo.outbox() map mailRepo.get
      } foreach { mail =>
        self ! ProcessMail(mail)
      }
    case ProcessMail(mail) => mailProvider.sendMail(mail)
    case unknown => throw new Exception("unknown message: %s".format(unknown))
  }
}
