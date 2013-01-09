package com.keepit.common.healthcheck

import scala.collection.mutable.MutableList
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
import play.api.Plugin
import play.api.templates.Html
import akka.util.Timeout
import akka.actor._
import akka.actor.Actor._
import akka.actor.ActorRef
import akka.util.duration._
import akka.pattern.ask
import akka.dispatch.Await
import play.api.libs.concurrent._
import org.joda.time.DateTime
import akka.dispatch.Future
import com.google.inject.Inject
import com.google.inject.Provider

case class HealthcheckError(error: Option[Throwable] = None, method: Option[String] = None,
    path: Option[String] = None, callType: CallType, errorMessage: Option[String] = None,
    id: ExternalId[HealthcheckError] = ExternalId(), createdAt: DateTime = currentDateTime) {
  def toHtml: String = {
    val message = new StringBuilder("%s: [%s] Error during call of type %s".format(createdAt, id, callType))
    method.map { m =>
      message ++= "<br/>http method [%s]".format(m)
    }
    path.map { p =>
      message ++= "<br/>path [%s]v".format(p)
    }
    errorMessage.map { em =>
      message ++= "<br/>error message: %s".format(em.replaceAll("\n", "\n<br/>"))
    }
    error.map { e =>
      message ++= "<br/>Exception %s stack trace: \n<br/>".format(e.toString())
      message ++= (e.getStackTrace() mkString "\n<br/> &nbsp; ")
      causeDisplay(e)
    }

    def causeDisplay(e: Throwable): Unit = {
      Option(e.getCause) map { cause =>
        message ++= "<br/>from cause: %s\n<br/>".format(cause.toString)
        message ++= (cause.getStackTrace() mkString "\n<br/> &nbsp; ")
        causeDisplay(cause)
      }
    }
    message.toString()
  }
}

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
case object Heartbeat
case object GetErrors

private[healthcheck] class HealthcheckActor(postOffice: PostOffice, services: FortyTwoServices) extends Actor with Logging {

  private var errors: List[HealthcheckError] = Nil
  private var errorCount = 0
  private var lastError: Option[DateTime] = None
  private val startupTime = currentDateTime

  def receive() = {
    case ReportErrorsAction =>
      if (errors.nonEmpty) {
        val message = Html(errors map {_.toHtml} mkString "\n<br/><hr/>")
        val subject = "ERROR REPORT: %s errors on %s version %s compiled on %s".format(
            errors.size, services.currentService, services.currentVersion, services.compilationTime)
        errors = Nil
        postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = subject, htmlBody = message.body, category = PostOffice.Categories.HEALTHCHECK))
      }
    case Heartbeat =>
      val now = currentDateTime
      val partOfDay = if (now.getHourOfDay() < 12) "morning" else "afternoon"
      val message = Html("Good %s!<br/>Time is %s, service %s version %s compiled at %s started on %s is happily running. Last error time was %s".
          format(partOfDay, now, services.currentService, services.currentVersion, services.compilationTime, startupTime, lastError.map(_.format).getOrElse("Never!")))
      val subject = "Heartbeat message from %s version %s".format(services.currentService, services.currentVersion)
      postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = subject, htmlBody = message.body, category = PostOffice.Categories.HEALTHCHECK))
    case ErrorCountSinceLastCheck =>
      val errorCountSinceLastCheck = errorCount
      sender ! errorCountSinceLastCheck
    case ResetErrorCount =>
      errorCount = 0
    case GetErrors =>
      sender ! errors
    case error: HealthcheckError =>
      errors = error :: errors
      errorCount = errorCount + 1
      lastError = Some(currentDateTime)
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait HealthcheckPlugin extends Plugin {
  def errorCountFuture(): Future[Int]
  def errorCount(): Int
  def errorsFuture(): Future[List[HealthcheckError]]
  def errors(): List[HealthcheckError]
  def resetErrorCount(): Unit
  def addError(error: HealthcheckError): HealthcheckError
  def reportStart(): ElectronicMail
  def reportStop(): ElectronicMail
}

class HealthcheckPluginImpl(system: ActorSystem, host: String, postOffice: PostOffice, services: FortyTwoServices) extends HealthcheckPlugin {

  implicit val actorTimeout = Timeout(5 seconds)

  private val actor = system.actorOf(Props { new HealthcheckActor(postOffice, services) })

  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    _cancellables = Seq(
      system.scheduler.schedule(0 seconds, 10 minutes, actor, ReportErrorsAction),
      system.scheduler.schedule(12 hours, 12 hours, actor, Heartbeat)
    )
  }
  override def onStop(): Unit = {
    _cancellables.map(_.cancel)
  }

  def errorCountFuture(): Future[Int] = (actor ? ErrorCountSinceLastCheck).mapTo[Int]

  def errorCount(): Int = Await.result(errorCountFuture(), 5 seconds)

  def errorsFuture(): Future[List[HealthcheckError]] = (actor ? GetErrors).mapTo[List[HealthcheckError]]

  def errors(): List[HealthcheckError] = Await.result(errorsFuture(), 20 seconds)

  def resetErrorCount(): Unit = actor ! ResetErrorCount

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
