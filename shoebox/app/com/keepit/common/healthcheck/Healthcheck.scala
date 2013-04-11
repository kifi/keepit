package com.keepit.common.healthcheck

import org.apache.commons.codec.binary.Base64
import java.security.MessageDigest
import scala.collection.mutable.MutableList

import com.keepit.common.actor.ActorFactory
import com.keepit.common.healthcheck.Healthcheck._
import com.keepit.common.db.ExternalId
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.EmailAddresses
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.time._
import com.keepit.common.plugin.SchedulingPlugin
import com.keepit.common.akka.FortyTwoActor

import play.api.templates.Html
import akka.util.Timeout
import akka.actor._
import akka.actor.Actor._
import akka.actor.ActorRef
import akka.pattern.ask
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent._
import org.joda.time.DateTime
import scala.concurrent.{Future, Await}
import com.google.inject.{Inject, Provider}
import scala.concurrent.duration._
import scala.collection.mutable.{HashTable => MMap}

object Healthcheck {

  sealed trait CallType

  case object API extends CallType
  case object EMAIL extends CallType
  case object SEARCH extends CallType
  case object FACEBOOK extends CallType
  case object BOOTSTRAP extends CallType
  case object INTERNAL extends CallType
}

case object ReportErrorsAction
case object ErrorCountSinceLastCheck
case object ResetErrorCount
case object GetErrors

case class HealthcheckHost(host: String) extends AnyVal {
  override def toString = host
}

case class HealthcheckErrorHistory(signature: HealthcheckErrorSignature, count: Int, countSinceLastAlert: Int, lastError: HealthcheckError)

class HealthcheckActor @Inject() (
    healthcheckPlugin: HealthcheckPlugin,
    postOffice: PostOffice,
    services: FortyTwoServices,
    host: HealthcheckHost)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  private val errors: MMap[HealthcheckErrorSignature, HealthcheckErrorHistory] = new MMap()

  def receive() = {
    case ReportErrorsAction =>
      if (errors.nonEmpty) {
        val titles = errors map {case(sig, errorList) =>
          s"${errorList.size} since last report, ${errorsSinceStart(sig)} since start of: ${errorList.last.titleHtml}"
        } mkString "\n<br/>"

        val messages = errors map {case(sig, errorList) =>
          s"${errorList.size} since last report, ${errorsSinceStart(sig)} since start of errorList sig ${sig.value}:\n<br/>${errorList.last.toHtml}"
        } mkString "\n<br/><hr/>"
        val subject = s"ERROR REPORT: ${errors.map(_._2.size).sum} errors since last report on ${services.currentService.name} ($host) version ${services.currentVersion} compiled on ${services.compilationTime}"
        errors = initErrors

        val htmlMessage = Html(s"$titles<br/><hr/><br/>$messages")

        postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = subject, htmlBody = htmlMessage.body, category = PostOffice.Categories.HEALTHCHECK))
      }
    case ErrorCountSinceLastCheck =>
      val errorCountSinceLastCheck = errorCount
      sender ! errorCountSinceLastCheck
    case ResetErrorCount =>
      errorCount = 0
    case GetErrors =>
      sender ! errors.values.flatten.toSeq
    case error: HealthcheckError =>
      val sigErrors = error :: errors(error.signature)
      errors = errors + (error.signature -> sigErrors)
      errorsSinceStart = errorsSinceStart + (error.signature -> (errorsSinceStart(error.signature) + 1))
      errorCount = errorCount + 1
      lastErrorTime = Some(currentDateTime)
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait HealthcheckPlugin extends SchedulingPlugin {
  def errorCountFuture(): Future[Int]
  def errorCount(): Int
  def errorsFuture(): Future[List[HealthcheckError]]
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
    postOffice: PostOffice,
    host: HealthcheckHost)
  extends HealthcheckPlugin {

  implicit val actorTimeout = Timeout(5 seconds)

  private lazy val actor = actorFactory.get()

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
     scheduleTask(actorFactory.system, 0 seconds, 10 minutes, actor, ReportErrorsAction)
  }

  def errorCountFuture(): Future[Int] = (actor ? ErrorCountSinceLastCheck).mapTo[Int]

  def errorCount(): Int = Await.result(errorCountFuture(), 5 seconds)

  def errorsFuture(): Future[List[HealthcheckError]] = (actor ? GetErrors).mapTo[List[HealthcheckError]]

  def errors(): Seq[HealthcheckError] = Await.result(errorsFuture(), 20 seconds)

  def resetErrorCount(): Unit = actor ! ResetErrorCount

  def reportErrors(): Unit = actor ! ReportErrorsAction

  def fakeError() = addError(HealthcheckError(None, None, None, Healthcheck.API, Some("Fake error")))

  def addError(error: HealthcheckError): HealthcheckError = {
    actor ! error
    error
  }

  override def reportStart() = {
    val subject = "Service %s [%s] started".format(services.currentService, services.currentVersion)
    val message = Html("Started at %s on %s. Service compiled at %s".format(currentDateTime, host, services.compilationTime))
    postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = subject, htmlBody = message.body, category = PostOffice.Categories.HEALTHCHECK))
  }

  override def reportStop() = {
    val subject = "Service %s [%s] stopped".format(services.currentService, services.currentVersion)
    val message = Html("Stopped at %s on %s. Service compiled at %s".format(currentDateTime, host, services.compilationTime))
    postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = subject, htmlBody = message.body, category = PostOffice.Categories.HEALTHCHECK))
  }
}
