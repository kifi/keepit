package com.keepit.common.mail

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
  def processMail(mailId: Id[ElectronicMail]) : Unit
  def processOutbox(): Unit
}

class MailSenderPluginImpl @Inject() (actorFactory: ActorFactory[MailSenderActor])
  extends Logging with MailSenderPlugin {

  private lazy val actor = actorFactory.get()

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actorFactory.system, 5 seconds, 5 seconds, actor, ProcessOutbox)
  }

  override def processOutbox(): Unit = actor ! ProcessOutbox
  override def processMail(mailId: Id[ElectronicMail]): Unit = actor ! ProcessMail(mailId)
}

private[mail] case class ProcessOutbox()
private[mail] case class ProcessMail(mailId: Id[ElectronicMail])

private[mail] class MailSenderActor @Inject() (
    db: Database,
    mailRepo: ElectronicMailRepo,
    healthcheckPlugin: HealthcheckPlugin,
    mailProvider: MailProvider)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case ProcessOutbox =>   {
      db.readOnly { implicit s =>
        mailRepo.outbox()
      } foreach { mail =>
        self ! ProcessMail(mail)
      }
    }
    case ProcessMail(mailId) => mailProvider.sendMail(mailId)
    case unknown => throw new Exception("unknown message: %s".format(unknown))
  }
}
