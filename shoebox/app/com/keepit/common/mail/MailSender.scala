package com.keepit.common.mail

import play.api.Play.current
import play.api.Plugin
import com.keepit.model.EmailAddress
import play.api.templates.Html
import play.api.libs.ws.WS
import play.api.libs.json._
import play.api.libs.ws._
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckError}
import com.keepit.common.db.CX
import com.keepit.inject._
import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.actor.Props
import akka.util.duration._
import akka.actor.ActorRef
import akka.actor.Cancellable
import com.google.inject.Provider
import play.api.libs.concurrent.Promise
import com.keepit.common.net.ClientResponse
import java.util.concurrent.TimeUnit
import com.google.inject.Inject

class MailSender @Inject() (system: ActorSystem)
  extends Logging with Plugin {

  def processMail(mail: ElectronicMail) = actor ! ProcessMail(mail, this)

  private val actor = system.actorOf(Props { new MailSenderActor() })
  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    _cancellables = Seq(
      system.scheduler.schedule(5 seconds, 5 seconds, actor, ProcessOutbox(this))
    )
  }

  override def onStop(): Unit = {
    _cancellables.map(_.cancel)
  }

  private[mail] def processOutbox(): Unit = {
    CX.withConnection { implicit c =>
      ElectronicMail.outbox()
    } foreach { mail =>
      processMail(mail)
    }
  }
}

private[mail] case class ProcessOutbox(sender: MailSender)
private[mail] case class ProcessMail(mail: ElectronicMail, sender: MailSender)

private[mail] class MailSenderActor() extends Actor with Logging {

  def receive() = {
    case ProcessOutbox(sender) => sender.processOutbox()
    case ProcessMail(mail, sender) => inject[SendgridMailProvider].sendMailToSendgrid(mail)
    case unknown => throw new Exception("unknown message: %s".format(unknown))
  }
}
