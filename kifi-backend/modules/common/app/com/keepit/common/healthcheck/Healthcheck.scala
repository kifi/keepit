  package com.keepit.common.healthcheck

import scala.collection.mutable.{HashMap => MMap}
import scala.concurrent.Await
import scala.concurrent.duration._

import com.google.inject.Inject
import com.google.inject.ImplementedBy
import com.keepit.common.strings._

import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{AlertingActor, UnsupportedActorMessage}
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import play.api.Plugin
import play.api.templates.Html
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import net.codingwell.scalaguice.ScalaModule
import scala.Some

object Healthcheck {

  sealed trait CallType

  case object API extends CallType
  case object EMAIL extends CallType
  case object SEARCH extends CallType
  case object FACEBOOK extends CallType
  case object BOOTSTRAP extends CallType
  case object INTERNAL extends CallType
  case object EXTENSION extends CallType

  def OPS_OF_THE_WEEK = {
    import EmailAddresses._
    val offset = if (currentDateTime.getDayOfWeek == 1 && currentDateTime.getHourOfDay < 9) -1 else 0
    val weekNumber = currentDateTime.getWeekOfWeekyear + offset
    val selflessEngineers = Map(
      41 -> LÉO,
      42 -> EISHAY,
      43 -> RAY,
      44 -> STEPHEN,
      45 -> ANDREW,
      47 -> LÉO,
      48 -> EISHAY,
      49 -> RAY,
      50 -> STEPHEN,
      51 -> ANDREW,
      52 -> LÉO,
      1  -> EISHAY,
      2  -> RAY,
      3  -> STEPHEN
    )
    selflessEngineers.getOrElse(weekNumber, ANDREW) // punishment if I don't keep this updated
  }
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


class MailSender @Inject() (sender: HealthcheckMailSender) {
  def sendMail(email: ElectronicMail) {
    sender.sendMail(email)
  }
}

class SendHealthcheckMail(history: AirbrakeErrorHistory, host: HealthcheckHost, sender: MailSender, services: FortyTwoServices) {
  def sendMail() {
    val last = history.lastError
    val subjectWithNumerics = s"[RPT-ERR][${services.currentService}] ${last.message.getOrElse("")} ${last.rootException}".abbreviate(512)
    val subject = "([0-9]+)".r.replaceAllIn(subjectWithNumerics, "*")
    val body = views.html.email.healthcheckMail(history, services.started.toStandardTimeString, host.host).body
    sender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
      subject = subject, htmlBody = body, category = PostOffice.Categories.System.HEALTHCHECK))
  }
}

case class AirbrakeErrorHistory(signature: AirbrakeErrorSignature, count: Int, countSinceLastAlert: Int, lastError: AirbrakeError) {
  def addError(error: AirbrakeError): AirbrakeErrorHistory = {
    require(error.signature == signature)
    copy(count = count + 1, countSinceLastAlert = countSinceLastAlert + 1, lastError = error)
  }
  def reset(): AirbrakeErrorHistory = copy(countSinceLastAlert = 0)
}

class HealthcheckActor @Inject() (
    services: FortyTwoServices,
    host: HealthcheckHost,
    emailSender: MailSender)
  extends AlertingActor {

  def alert(reason: Throwable, message: Option[Any]) = self ! error(reason, message)

  private val errors: MMap[AirbrakeErrorSignature, AirbrakeErrorHistory] = new MMap()

  def receive() = {
    case ReportErrorsAction =>
      errors.values filter { _.countSinceLastAlert > 0 } foreach { history =>
        errors(history.signature) = history.reset()
        new SendHealthcheckMail(history, host, emailSender, services).sendMail()
      }
    case GetErrors =>
      val lastErrors: Seq[AirbrakeError] = errors.values map {history => history.lastError} toSeq;
      sender ! lastErrors
    case ErrorCount => sender ! errors.values.foldLeft(0)(_ + _.count)
    case ResetErrorCount => errors.clear()
    case error: AirbrakeError =>
      val signature = error.signature
      val history = errors.contains(signature) match {
        case false =>
          val newHistory = AirbrakeErrorHistory(signature, 1, 0, error)
          newHistory
        case true =>
          errors(signature).addError(error)
      }
      errors(signature) = history
    case email: ElectronicMail => emailSender.sendMail(email)

    case m => throw new UnsupportedActorMessage(m)
  }
}

trait HealthcheckPlugin extends Plugin {
  def errorCount(): Int
  def errors(): Seq[AirbrakeError]
  def resetErrorCount(): Unit
  def addError(error: AirbrakeError): AirbrakeError
  def reportStart(): ElectronicMail
  def reportStop(): ElectronicMail
  def reportErrors(): Unit
  var isWarm = false
  def warmUp(benchmarkRunner: BenchmarkRunner): Unit = {isWarm = true}
}

class HealthcheckPluginImpl @Inject() (
    actor: ActorInstance[HealthcheckActor],
    services: FortyTwoServices,
    host: HealthcheckHost)
  extends HealthcheckPlugin with SchedulingPlugin with Logging {

  val schedulingProperties = SchedulingProperties.AlwaysEnabled
  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actor.system, 0 seconds, 30 minutes, actor.ref, ReportErrorsAction)
  }

  def errorCount(): Int = Await.result((actor.ref ? ErrorCount).mapTo[Int], 1 seconds)

  def errors(): Seq[AirbrakeError] = Await.result((actor.ref ? GetErrors).mapTo[List[AirbrakeError]], 1 seconds)

  def resetErrorCount(): Unit = actor.ref ! ResetErrorCount

  def reportErrors(): Unit = actor.ref ! ReportErrorsAction

  def addError(error: AirbrakeError): AirbrakeError = {
    log.error(s"Healthcheck logged error: ${error}")
    actor.ref ! error
    error
  }

  override def reportStart() = {
    val subject = s"Service ${services.currentService} started"
    val message = Html(s"Service version ${services.currentVersion} started at ${currentDateTime} on $host. Service compiled at ${services.compilationTime}")
    val email = (ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
        subject = subject, htmlBody = message.body,
        category = PostOffice.Categories.System.HEALTHCHECK))
    actor.ref ! email
    email
  }

  override def reportStop() = {
    val subject = s"Service ${services.currentService} stopped"
    val message = Html(s"Service version ${services.currentVersion} stopped at ${currentDateTime} on $host. Service compiled at ${services.compilationTime}")
    val email = (ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.ENG),
        subject = subject, htmlBody = message.body,
        category = PostOffice.Categories.System.HEALTHCHECK))
    actor.ref ! email
    email
  }

  override def warmUp(benchmarkRunner: BenchmarkRunner) : Unit = {
    log.info("going to sleep for one minute to make sure all plugins are ready to go")
    log.info(s"benchmark 1: ${benchmarkRunner.runBenchmark()}")
    Thread.sleep(30 * 1000)//30 sec
    log.info(s"benchmark 2: ${benchmarkRunner.runBenchmark()}")
    log.info("ok, i'm warmed up and ready to roll!")
    super.warmUp(benchmarkRunner)
  }
}
