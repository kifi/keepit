package com.keepit.common.healthcheck

import scala.collection.mutable.{HashMap => MMap}
import scala.concurrent.Await
import scala.concurrent.duration._

import com.google.inject.Inject
import com.google.inject.ImplementedBy
import com.keepit.common.actor.ActorFactory
import com.keepit.common.akka.AlertingActor
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.mail.EmailAddresses
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.LocalPostOffice
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._


import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.templates.Html
import com.keepit.common.mail.RemotePostOffice

object Healthcheck {

  sealed trait CallType

  case object API extends CallType
  case object EMAIL extends CallType
  case object SEARCH extends CallType
  case object FACEBOOK extends CallType
  case object BOOTSTRAP extends CallType
  case object INTERNAL extends CallType
  case object EXTENSION extends CallType
}

case object ReportErrorsAction
case object ErrorCount
case object ResetErrorCount
case object GetErrors

case class HealthcheckHost(host: String) extends AnyVal {
  override def toString = host
}

@ImplementedBy(classOf[RemoteHealthcheckMailSender])
trait HealthcheckMailSender {
  def sendMail(email: ElectronicMail)
}

class RemoteHealthcheckMailSender @Inject() (postOffice: RemotePostOffice) extends HealthcheckMailSender {
  def sendMail(email: ElectronicMail) = postOffice.queueMail(email)
}
class LocalHealthcheckMailSender @Inject() (postOffice: LocalPostOffice, db: Database) extends HealthcheckMailSender {
  def sendMail(email: ElectronicMail) = db.readWrite(postOffice.sendMail(email)(_))
}

class MailSender @Inject() (sender: HealthcheckMailSender) {
  def sendMail(email: ElectronicMail) {
    sender.sendMail(email)
  }
}

class SendHealthcheckMail(history: HealthcheckErrorHistory, host: HealthcheckHost, sender: MailSender, services: FortyTwoServices) {

  def sendMail() {
    if (history.lastError.callType == Healthcheck.EXTENSION) return
    if (history.count == 1) sendAsanaMail()
    else sendRegularMail()
  }

  private def sendRegularMail() {
      val subject = s"[REPEATING ERROR][${services.currentService}] ${history.lastError.subjectName}"
      val body = views.html.email.healthcheckMail(history, services.started.toStandardTimeString, host.host).body
      sender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
        subject = subject, htmlBody = body, category = PostOffice.Categories.HEALTHCHECK))
  }

  private def sendAsanaMail() {
      val started = services.started.toStandardTimeString
      val subject = s"[${services.currentService}] ${history.lastError.subjectName}"
      sender.sendMail(ElectronicMail(
        from = EmailAddresses.EISHAY,
        to = EmailAddresses.ASANA_PROD_HEALTH::EmailAddresses.EISHAY::Nil,
        cc = EmailAddresses.ENG_EMAILS,
        subject = subject,
        htmlBody = views.html.email.healthcheckMail(history, started, host.host).body,
        textBody = Some(views.html.email.healthcheckAsanaMail(history, started, host.host).body),
        category = PostOffice.Categories.ASANA_HEALTHCHECK))
  }
}

case class HealthcheckErrorHistory(signature: HealthcheckErrorSignature, count: Int, countSinceLastAlert: Int, lastError: HealthcheckError) {
  def addError(error: HealthcheckError): HealthcheckErrorHistory = {
    require(error.signature == signature)
    copy(count = count + 1, countSinceLastAlert = countSinceLastAlert + 1, lastError = error)
  }
  def reset(): HealthcheckErrorHistory = copy(countSinceLastAlert = 0)
}

class HealthcheckActor @Inject() (
    services: FortyTwoServices,
    host: HealthcheckHost,
    emailSender: MailSender)
  extends AlertingActor {

  def alert(reason: Throwable, message: Option[Any]) = self ! error(reason, message)

  private val errors: MMap[HealthcheckErrorSignature, HealthcheckErrorHistory] = new MMap()

  def receive() = {
    case ReportErrorsAction =>
      errors.values filter { _.countSinceLastAlert > 0 } foreach { history =>
        errors(history.signature) = history.reset()
        new SendHealthcheckMail(history, host, emailSender, services).sendMail()
      }
    case GetErrors =>
      val lastErrors: Seq[HealthcheckError] = errors.values map {history => history.lastError} toSeq;
      sender ! lastErrors
    case ErrorCount => sender ! errors.values.foldLeft(0)(_ + _.count)
    case ResetErrorCount => errors.clear()
    case error: HealthcheckError =>
      val signature = error.signature
      val history = errors.contains(signature) match {
        case false =>
          val newHistory = HealthcheckErrorHistory(signature, 1, 0, error)
          new SendHealthcheckMail(newHistory, host, emailSender, services).sendMail()
          newHistory
        case true =>
          errors(signature).addError(error)
      }
      errors(signature) = history
    case email: ElectronicMail => emailSender.sendMail(email)

    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait HealthcheckPlugin extends SchedulingPlugin {
  def errorCount(): Int
  def errors(): Seq[HealthcheckError]
  def resetErrorCount(): Unit
  def addError(error: HealthcheckError): HealthcheckError
  def reportStart(): ElectronicMail
  def reportStop(): ElectronicMail
  def reportErrors(): Unit
}

class HealthcheckPluginImpl @Inject() (
    actorFactory: ActorFactory[HealthcheckActor],
    services: FortyTwoServices,
    host: HealthcheckHost,
    val schedulingProperties: SchedulingProperties)
  extends HealthcheckPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  private lazy val actor = actorFactory.get()

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
     scheduleTask(actorFactory.system, 0 seconds, 10 minutes, actor, ReportErrorsAction)
  }

  def errorCount(): Int = Await.result((actor ? ErrorCount).mapTo[Int], 1 seconds)

  def errors(): Seq[HealthcheckError] = Await.result((actor ? GetErrors).mapTo[List[HealthcheckError]], 1 seconds)

  def resetErrorCount(): Unit = actor ! ResetErrorCount

  def reportErrors(): Unit = actor ! ReportErrorsAction

  def addError(error: HealthcheckError): HealthcheckError = {
    log.error(s"Healthcheck logged error: ${error}")
    actor ! error
    error
  }

  override def reportStart() = {
    val subject = s"Service ${services.currentService} started"
    val message = Html(s"Service version ${services.currentVersion} started at ${currentDateTime} on $host. Service compiled at ${services.compilationTime}")
    val email = (ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
        subject = subject, htmlBody = message.body,
        category = PostOffice.Categories.HEALTHCHECK))
    actor ! email
    email
  }

  override def reportStop() = {
    val subject = s"Service ${services.currentService} stopped"
    val message = Html(s"Service version ${services.currentVersion} stopped at ${currentDateTime} on $host. Service compiled at ${services.compilationTime}")
    val email = (ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
        subject = subject, htmlBody = message.body,
        category = PostOffice.Categories.HEALTHCHECK))
    actor ! email
    email
  }
}
