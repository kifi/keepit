package com.keepit.common.healthcheck

import org.joda.time.{ Days, LocalDate, DateTime }

import scala.collection.mutable.{ HashMap => MMap }
import scala.concurrent.Await
import scala.concurrent.duration._

import com.google.inject.Inject
import com.google.inject.ImplementedBy
import com.keepit.common.strings._

import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ AlertingActor, UnsupportedActorMessage }
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.plugin.SchedulerPlugin
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import play.api.Mode._
import play.api.templates.Html
import com.keepit.model.NotificationCategory
import java.io.File

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
case object CheckDiskSpace
case object ErrorCount
case object ResetErrorCount
case object GetErrors
case object CheckUpdateStatusOfService

case class HealthcheckHost(host: String) extends AnyVal {
  override def toString = host
}

case class HealthCheckConf(
  startupSleep: Int // seconds
  ) extends AnyVal

class MailSender @Inject() (
    sender: SystemAdminMailSender,
    playMode: Mode) extends Logging {
  def sendMail(email: ElectronicMail): Unit = playMode match {
    case Prod => sender.sendMail(email)
    case _ => log.info(s"skip sending email: $email")
  }
}

class SendHealthcheckMail(history: AirbrakeErrorHistory, host: HealthcheckHost, sender: MailSender, services: FortyTwoServices) {
  def sendMail() {
    val last = history.lastError
    val subjectWithNumerics = s"[RPT-ERR][${services.currentService}] ${last.message.getOrElse("")} ${last.rootException}"
    val subject = "([0-9]+)".r.replaceAllIn(subjectWithNumerics, "*").abbreviate(512)
    val body = views.html.email.healthcheckMail(history, services.started.withZone(zones.PT).toStandardTimeString, host.host).body
    sender.sendMail(ElectronicMail(from = SystemEmailAddress.ENG, to = List(SystemEmailAddress.ENG),
      subject = subject, htmlBody = body, category = NotificationCategory.System.HEALTHCHECK))
  }

  def sendOutOfDateMail() {
    val subject = s"${services.currentService} out of date for 3 days"
    val body = s"None"
    sender.sendMail(ElectronicMail(from = SystemEmailAddress.ENG, to = List(SystemEmailAddress.ENG),
      subject = subject, htmlBody = body, category = NotificationCategory.System.HEALTHCHECK))
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
      val lastErrors: Seq[AirbrakeError] = errors.values map { history => history.lastError } toSeq;
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
    case CheckDiskSpace =>
      val GB = 1024 * 1024 * 1024
      val usableDiskSpace = new File(".").getUsableSpace
      if (usableDiskSpace < 1 * GB) { // less then 2gb of available disk space
        self ! AirbrakeError(message = Some(s"machine has only ${(usableDiskSpace * 1d) / GB}gb free of usable disk space !!!"), panic = true)
      } else if (usableDiskSpace < 2 * GB) { // less then 2gb of available disk space
        self ! AirbrakeError(message = Some(s"machine has only ${(usableDiskSpace * 1d) / GB}gb free of usable disk space"))
      }
    case CheckUpdateStatusOfService =>
      val currentDate: DateTime = currentDateTime
      val lastCompilationDate: DateTime = services.compilationTime
      val betweenDays = Days.daysBetween(currentDate, lastCompilationDate).getDays
      if (betweenDays >= 3) {
        new SendHealthcheckMail(null, host, emailSender, services).sendOutOfDateMail()
      }
    case m => throw new UnsupportedActorMessage(m)
  }
}

trait HealthcheckPlugin {
  def errorCount(): Int
  def errors(): Seq[AirbrakeError]
  def resetErrorCount(): Unit
  def addError(error: AirbrakeError): AirbrakeError
  def reportStart(): ElectronicMail
  def reportStop(): ElectronicMail
  def reportErrors(): Unit
  var isWarm = false
  def warmUp(benchmarkRunner: BenchmarkRunner): Unit = { isWarm = true }
}

class HealthcheckPluginImpl @Inject() (
    actor: ActorInstance[HealthcheckActor],
    services: FortyTwoServices,
    host: HealthcheckHost,
    val scheduling: SchedulingProperties,
    isCanary: Boolean,
    amazonSimpleMailProvider: AmazonSimpleMailProvider,
    healthCheckConf: HealthCheckConf) extends HealthcheckPlugin with SchedulerPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTaskOnAllMachines(actor.system, 0 seconds, 30 minutes, actor.ref, ReportErrorsAction)
    scheduleTaskOnAllMachines(actor.system, 0 seconds, 60 minutes, actor.ref, CheckDiskSpace)
    scheduleTaskOnAllMachines(actor.system, 3 days, 1 days, actor.ref, CheckUpdateStatusOfService)
  }

  def errorCount(): Int = Await.result((actor.ref ? ErrorCount).mapTo[Int], 1 seconds)

  def errors(): Seq[AirbrakeError] = Await.result((actor.ref ? GetErrors).mapTo[List[AirbrakeError]], 1 seconds)

  def resetErrorCount(): Unit = actor.ref ! ResetErrorCount

  def reportErrors(): Unit = actor.ref ! ReportErrorsAction

  def addError(error: AirbrakeError): AirbrakeError = {
    log.error(s"Healthcheck logged error: $error")
    actor.ref ! error
    error
  }

  override def reportStart() = {
    val subject = s"Service ${services.currentService} started"
    val message = Html(s"Service version ${services.currentVersion} started at $currentDateTime on $host. Service compiled at ${services.compilationTime}")
    val email = ElectronicMail(from = SystemEmailAddress.ENG, to = List(SystemEmailAddress.ENG),
      subject = subject, htmlBody = message.body,
      category = NotificationCategory.System.HEALTHCHECK)

    if (!isCanary) {
      actor.ref ! email
    }
    email
  }

  override def reportStop() = {
    val subject = s"Service ${services.currentService} stopped"
    val message = Html(s"Service version ${services.currentVersion} stopped at $currentDateTime on $host. Service compiled at ${services.compilationTime}")
    val email = ElectronicMail(from = SystemEmailAddress.ENG, to = List(SystemEmailAddress.ENG),
      subject = subject, htmlBody = message.body,
      category = NotificationCategory.System.HEALTHCHECK)
    if (!isCanary) {
      actor.ref ! email
    }
    email
  }

  val sleep = healthCheckConf.startupSleep

  override def warmUp(benchmarkRunner: BenchmarkRunner): Unit = {
    log.info(s"going to sleep for $sleep seconds to make sure all plugins are ready to go")
    log.info(s"benchmark 1: ${benchmarkRunner.runBenchmark()}")
    Thread.sleep(sleep * 1000)
    log.info(s"benchmark 2: ${benchmarkRunner.runBenchmark()}")
    log.info("ok, i'm warmed up and ready to roll!")
    super.warmUp(benchmarkRunner)
  }
}

