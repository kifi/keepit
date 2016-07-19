package com.keepit.common.healthcheck

import java.io.File

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ AlertingActor, UnsupportedActorMessage }
import com.keepit.common.cache.{ MemcachedCache, GlobalCacheStatistics }
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.model.NotificationCategory
import org.joda.time.{ DateTime, Days }
import play.api.Mode._

import scala.collection.mutable.{ HashMap => MMap }
import scala.concurrent.Await
import scala.concurrent.duration._

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
case object CheckCacheMissRatio
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
    case _ => log.info(s"skip sending email: $email because it is dev mode.")
  }
}

class SendHealthcheckMail(history: AirbrakeErrorHistory, host: HealthcheckHost, sender: MailSender, services: FortyTwoServices) {
  def sendMail() {
    val last = history.lastError
    val subjectWithNumerics = s"[RPT-ERR][${services.currentService}] ${last.message.getOrElse("")} ${last.rootException}"
    val subject = "([0-9]+)".r.replaceAllIn(subjectWithNumerics, "*").abbreviate(512)
    val body = views.html.email.healthcheckMail(history, services.started.withZone(zones.PT).toStandardTimeString, host.host).body
    sender.sendMail(ElectronicMail(from = SystemEmailAddress.ENG42, to = List(SystemEmailAddress.ENG42),
      subject = subject, htmlBody = body, category = NotificationCategory.System.HEALTHCHECK))
  }
}

class SendOutOfDateMail(sender: MailSender, services: FortyTwoServices) extends Logging {
  def sendMail() {
    val subject = s"${services.currentService} out of date for 10 days"
    val body = ""
    sender.sendMail(ElectronicMail(from = SystemEmailAddress.ENG42, to = List(SystemEmailAddress.ENG42),
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
  emailSender: MailSender,
  globalCacheStatistics: GlobalCacheStatistics)

    extends AlertingActor with Logging {
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
    case email: ElectronicMail =>
      emailSender.sendMail(email)
    case CheckCacheMissRatio =>
      val cacheName = MemcachedCache.name
      val misses = globalCacheStatistics.missRatios(minSample = 1000, minRatio = 20, cacheName) // I rather have minRatio set to 2% but one step at a time...
      if (misses.nonEmpty) {
        val message = misses.map {
          case (key, ratio) =>
            val keyName = key.substring(cacheName.length + 1)
            s"$keyName:$ratio%(h${globalCacheStatistics.hitCount(key)},m${globalCacheStatistics.missCount(key)},s${globalCacheStatistics.setCount(key)})"
        } mkString ", "
        self ! AirbrakeError(message = Some(s"there are too many cache misses: $message"))
      }
    case CheckDiskSpace =>
      val GB = 1024 * 1024 * 1024
      val usableDiskSpace = new File(".").getUsableSpace
      if (usableDiskSpace < 2 * GB) {
        self ! AirbrakeError(message = Some(s"machine has only ${(usableDiskSpace * 1d) / GB}gb free of usable disk space !!!"), panic = true)
      } else if (usableDiskSpace < 10 * GB) {
        self ! AirbrakeError(message = Some(s"machine has only ${(usableDiskSpace * 1d) / GB}gb free of usable disk space"))
      }
    case CheckUpdateStatusOfService =>
      val currentDate: DateTime = currentDateTime
      val lastCompilationDate: DateTime = services.compilationTime
      val betweenDays = Days.daysBetween(lastCompilationDate, currentDate).getDays
      if (betweenDays >= 10) {
        new SendOutOfDateMail(emailSender, services).sendMail()
      }
    case m => throw new UnsupportedActorMessage(m)
  }
}

trait HealthcheckPlugin {
  def errorCount(): Int
  def errors(): Seq[AirbrakeError]
  def resetErrorCount(): Unit
  def addError(error: AirbrakeError): AirbrakeError
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
  override def onStart() { //keep me alive - ish!
    scheduleTaskOnAllMachines(actor.system, 0 seconds, 3 hours, actor.ref, ReportErrorsAction)
    scheduleTaskOnAllMachines(actor.system, 0 seconds, 60 minutes, actor.ref, CheckDiskSpace)
    //    scheduleTaskOnAllMachines(actor.system, 10 days, 2 day, actor.ref, CheckUpdateStatusOfService)
    //    scheduleTaskOnAllMachines(actor.system, 1 hour, 1 day, actor.ref, CheckCacheMissRatio)
  }

  def errorCount(): Int = Await.result((actor.ref ? ErrorCount).mapTo[Int], 1 seconds)

  def errors(): Seq[AirbrakeError] = Await.result((actor.ref ? GetErrors).mapTo[List[AirbrakeError]], 1 seconds)

  def resetErrorCount(): Unit = actor.ref ! ResetErrorCount

  def reportErrors(): Unit = actor.ref ! ReportErrorsAction

  def addError(error: AirbrakeError): AirbrakeError = {
    actor.ref ! error
    error
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

