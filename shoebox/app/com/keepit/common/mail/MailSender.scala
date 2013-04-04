package com.keepit.common.mail

import play.api.Play.current
import com.keepit.model.EmailAddress
import play.api.templates.Html
import play.api.libs.ws.WS
import play.api.libs.json._
import play.api.libs.ws._

import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.actor.ActorFactory
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.inject._
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.plugin.SchedulingPlugin
import com.keepit.common.net.ClientResponse

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.actor.Props
import play.api.libs.concurrent.Execution.Implicits._
import akka.actor.ActorRef
import akka.actor.Cancellable
import play.api.libs.concurrent.Promise
import java.util.concurrent.TimeUnit
import com.google.inject.Provider
import com.google.inject.Inject
import scala.concurrent.duration._

trait MailSenderPlugin extends SchedulingPlugin {
  def processMail(mail: ElectronicMail) : Unit
  def processOutbox(): Unit
}

class MailSenderPluginImpl @Inject() (
    actorFactory: ActorFactory[MailSenderActor],
    db: Database,
    mailRepo: ElectronicMailRepo)
  extends Logging with MailSenderPlugin {

  override def processMail(mail: ElectronicMail): Unit = actor ! ProcessMail(mail, this)

  private lazy val actor = actorFactory.get()
  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actorFactory.system, 5 seconds, 5 seconds, actor, ProcessOutbox(this))
  }

  override def processOutbox(): Unit = {
    db.readOnly { implicit s =>
      mailRepo.outbox
    } foreach { mail =>
      processMail(mail)
    }
  }
}

private[mail] case class ProcessOutbox(sender: MailSenderPlugin)
private[mail] case class ProcessMail(mail: ElectronicMail, sender: MailSenderPlugin)

private[mail] class MailSenderActor @Inject() (
    healthcheckPlugin: HealthcheckPlugin)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case ProcessOutbox(sender) => sender.processOutbox()
    case ProcessMail(mail, sender) => inject[SendgridMailProvider].sendMailToSendgrid(mail)
    case unknown => throw new Exception("unknown message: %s".format(unknown))
  }
}
