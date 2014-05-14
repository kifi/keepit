package com.keepit.common.concurrent

import com.google.inject.Inject
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}
import play.api.Plugin
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{SystemAdminMailSender, AirbrakeNotifier}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{UnsupportedActorMessage, FortyTwoActor}
import com.keepit.common.mail.{EmailAddresses, ElectronicMail}
import com.keepit.model.NotificationCategory
import scala.concurrent.duration._

case object CheckFJContext

class ForkJoinExecContextMonitor @Inject() (
  airbrake:AirbrakeNotifier,
  systemAdminMailSender:SystemAdminMailSender
) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case CheckFJContext => checkFJContext()
    case m => throw new UnsupportedActorMessage(m)
  }

  def checkFJContext():Unit = {
    val fj = com.keepit.common.concurrent.ExecutionContext.fjPool
    log.debug(s"[checkFJContext] #queuedSubmission=${fj.getQueuedSubmissionCount} #queuedTasks=${fj.getQueuedTaskCount} fj=${fj}")
    if (fj.getQueuedSubmissionCount > Runtime.getRuntime.availableProcessors * 5) { // todo: tweak; airbrake if this proves useful
      systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.ENG,
        to = Seq(EmailAddresses.RAY),
        category = NotificationCategory.System.HEALTHCHECK, // may need a new category
        subject = s"fjPool-queuedSubmission=${fj.getQueuedSubmissionCount}",
        htmlBody = s"ForkJoinPool-backed context queued submission count exceeded threshold $fj"))
    }
  }
}

trait ForkJoinExecContextPlugin extends Plugin

class ForkJoinExecContextPluginImpl @Inject() (
  airbrake: AirbrakeNotifier,
  systemAdminMailSender: SystemAdminMailSender,
  actor: ActorInstance[ForkJoinExecContextMonitor],
  val scheduling:SchedulingProperties) extends ForkJoinExecContextPlugin with SchedulerPlugin with Logging {

  override def enabled: Boolean = true
  override def onStart() {
    log.info(s"[onStart] starting ForkJoinExecContextPluginImpl")
    scheduleTaskOnAllMachines(actor.system, 45 seconds, 5 seconds, actor.ref, CheckFJContext)
  }
  override def onStop() {
    log.info(s"[onStop] ForkJoinExecContextPluginImpl stopped")
    super.onStop()
  }
}