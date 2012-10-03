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
      message ++= (e.getStackTrace() mkString "\n<br/>") 
    } 
    message.toString()
  }
} 

object Healthcheck {

  sealed trait CallType
  
  case object API extends CallType
  case object EMAIL extends CallType
  case object FACEBOOK extends CallType
  case object BOOTSTRAP extends CallType
}

case object ReportErrorsAction
case object ErrorCountSinceLastCheck
case object Heartbeat

private[healthcheck] class HealthcheckActor(postOffice: PostOffice) extends Actor with Logging {
  
  private var errors: List[HealthcheckError] = Nil
  private var errorCount = 0
  private var lastError: Option[DateTime] = None
  private val startupTime = currentDateTime
  
  def receive() = {
    case ReportErrorsAction =>
      if (errors.nonEmpty) {
        val message = Html(errors map {_.toHtml} mkString "\n<br/>")
        val subject = "ERROR REPORT: %s errors on %s version %s compiled on %s".format(
            errors.size, FortyTwoServices.currentService, FortyTwoServices.currentVersion, FortyTwoServices.compilationTime)
        errors = Nil
        postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = subject, htmlBody = message.body))
      }
    case Heartbeat =>
      val now = currentDateTime
      val partOfDay = if (now.getHourOfDay() < 12) "morning" else "afternoon" 
      val message = Html("Good %s!<br/>Time is %s, service %s version %s compiled at %s started on %s is happily running. Last error time was %s".
          format(partOfDay, now, FortyTwoServices.currentService, FortyTwoServices.currentVersion, FortyTwoServices.compilationTime, startupTime, lastError.map(_.format).getOrElse("Never!")))
      val subject = "Heartbeat message from %s version %s".format(FortyTwoServices.currentService, FortyTwoServices.currentVersion)
      postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = subject, htmlBody = message.body))
    case ErrorCountSinceLastCheck =>
      val errorCountSinceLastCheck = errorCount
      errorCount = 0
      sender ! errorCountSinceLastCheck
    case error: HealthcheckError => 
      errors = error :: errors
      errorCount = errorCount + 1
      lastError = Some(currentDateTime)
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait Healthcheck extends Plugin {
  def errorCountFuture(): Future[Int]
  def addError(error: HealthcheckError): HealthcheckError 
  def reportStart(): ElectronicMail
  def reportStop(): ElectronicMail
}

class HealthcheckImpl(system: ActorSystem, host: String, postOffice: PostOffice) extends Healthcheck {
  
  implicit val actorTimeout = Timeout(5 seconds)
  
  private val actor = system.actorOf(Props { new HealthcheckActor(postOffice) })
  
  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    _cancellables = Seq(
      system.scheduler.schedule(0 seconds, 1 minutes, actor, ReportErrorsAction),
      system.scheduler.schedule(12 hours, 12 hours, actor, Heartbeat)
    )
  }
  override def onStop(): Unit = {
    _cancellables.map(_.cancel)
  }
  
  def errorCountFuture(): Future[Int] = (actor ? ErrorCountSinceLastCheck).mapTo[Int]
  
  def addError(error: HealthcheckError): HealthcheckError = {
    actor ! error
    error
  }

  override def reportStart() = {
    var subject = "Service %s [%s] started".format(FortyTwoServices.currentService, FortyTwoServices.currentVersion)
    var message = Html("Started at %s on %s. Service compiled at %s".format(currentDateTime, host, FortyTwoServices.compilationTime))
    postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = subject, htmlBody = message.body))
  }
  
  override def reportStop() = {
    var subject = "Service %s [%s] stopped".format(FortyTwoServices.currentService, FortyTwoServices.currentVersion)
    var message = Html("Stopped at %s on %s. Service compiled at %s".format(currentDateTime, host, FortyTwoServices.compilationTime))
    postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = subject, htmlBody = message.body))
  }
}
