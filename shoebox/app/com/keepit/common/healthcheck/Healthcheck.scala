package com.keepit.common.healthcheck

import scala.collection.mutable.{HashMap => MMap}
import scala.concurrent.Await
import scala.concurrent.duration._

import com.google.inject.Inject
import com.keepit.common.actor.ActorFactory
import com.keepit.common.akka.AlertingActor
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.mail.EmailAddresses
import com.keepit.common.mail.PostOffice
import com.keepit.common.plugin.SchedulingPlugin
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.templates.Html

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
case object ErrorCount
case object ResetErrorCount
case object GetErrors

case class HealthcheckHost(host: String) extends AnyVal {
  override def toString = host
}

case class SendHealthcheckMail(history: HealthcheckErrorHistory, host: HealthcheckHost) {
  def sendMail(db: Database, postOffice: PostOffice, services: FortyTwoServices){
    db.readWrite { implicit s =>
      val subject = s"ERROR: [${services.currentService}/$host] ${history.lastError.subjectName}"
      val body = views.html.email.healthcheckMail(history).body
      postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG,
        subject = subject, htmlBody = body, category = PostOffice.Categories.HEALTHCHECK))
    }
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
    postOffice: PostOffice,
    services: FortyTwoServices,
    host: HealthcheckHost,
    db: Database)
  extends AlertingActor {

  def alert(reason: Throwable, message: Option[Any]) = self ! error(reason, message)

  private val errors: MMap[HealthcheckErrorSignature, HealthcheckErrorHistory] = new MMap()

  def receive() = {
    case ReportErrorsAction =>
      errors.values filter { _.countSinceLastAlert > 0 } foreach { history =>
        errors(history.signature) = history.reset()
        self ! SendHealthcheckMail(history, host)
      }
    case GetErrors =>
      val lastErrors: Seq[HealthcheckError] = errors.values map {history => history.lastError} toSeq;
      sender ! lastErrors
    case sendMail: SendHealthcheckMail => sendMail.sendMail(db, postOffice, services)
    case ErrorCount => sender ! errors.values.foldLeft(0)(_ + _.count)
    case ResetErrorCount => errors.clear()
    case error: HealthcheckError =>
      val signature = error.signature
      val history = errors.contains(signature) match {
        case false =>
          val newHistory = HealthcheckErrorHistory(signature, 1, 0, error)
          self ! SendHealthcheckMail(newHistory, host)
          newHistory
        case true =>
          errors(signature).addError(error)
      }
      errors(signature) = history
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
    postOffice: PostOffice,
    host: HealthcheckHost,
    db: Database)
  extends HealthcheckPlugin {

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
    actor ! error
    error
  }

  override def reportStart() = db.readWrite { implicit s =>
    val subject = "Service %s [%s] started".format(services.currentService, services.currentVersion)
    val message = Html("Started at %s on %s. Service compiled at %s".format(currentDateTime, host, services.compilationTime))
    postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = subject, htmlBody = message.body, category = PostOffice.Categories.HEALTHCHECK))
  }

  override def reportStop() = db.readWrite { implicit s =>
    val subject = "Service %s [%s] stopped".format(services.currentService, services.currentVersion)
    val message = Html("Stopped at %s on %s. Service compiled at %s".format(currentDateTime, host, services.compilationTime))
    postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.ENG, subject = subject, htmlBody = message.body, category = PostOffice.Categories.HEALTHCHECK))
  }
}
