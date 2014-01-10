package com.keepit.common.plugin

import com.keepit.common.logging.Logging
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.actor.ActorInstance

import akka.actor.{ActorSystem, Cancellable, ActorRef}
import play.api.Plugin

import us.theatr.akka.quartz._

sealed trait SchedulingEnabled

object SchedulingEnabled {
  case object Always extends SchedulingEnabled
  case object Never extends SchedulingEnabled
  case object LeaderOnly extends SchedulingEnabled
}

class NamedCancellable(underlying:Cancellable, taskName:String) extends Cancellable {
  def name() = taskName
  def cancel() = underlying.cancel
  def isCancelled: Boolean = underlying.isCancelled
}

trait SchedulingPlugin extends Plugin with Logging {

  def schedulingProperties: SchedulingProperties

  private var _cancellables: Seq[NamedCancellable] = Seq()

  private def execute(f: => Unit, taskName: String): Unit =
    if (schedulingProperties.allowScheduling) {log.info(s"executing scheduled task: $taskName"); f}
    else log.info(s"scheduling disabled, block execution of scheduled task: $taskName")

  def scheduleTask(system: ActorSystem, initialDelay: FiniteDuration, frequency: FiniteDuration, taskName: String)(f: => Unit): Unit =
    if (!schedulingProperties.neverAllowScheduling) {
      log.info(s"Registering $taskName in scheduler")
      val cancellable = system.scheduler.schedule(initialDelay, frequency) {
        execute(f, taskName)
      }
      _cancellables :+= new NamedCancellable(cancellable, taskName)
    } else log.info(s"permanently disable scheduling for task: $taskName")

  def cronTask(quartz: ActorInstance[QuartzActor], receiver: ActorRef, cron: String, message: Any): Unit = {
    val taskName = s"cron message $message to actor $receiver"
    if (!schedulingProperties.neverAllowScheduling) {
      log.info(s"Scheduling $taskName in Cron")
      val spigot = new Spigot {
        def open = schedulingProperties.allowScheduling
      }
      quartz.ref ! AddCronSchedule(receiver, cron, message, false, spigot)
    } else log.info(s"permanently disable cron for task: $taskName")
  }

  def scheduleTaskOnce(system: ActorSystem, initialDelay: FiniteDuration, taskName: String)(f: => Unit): Unit =
    if (!schedulingProperties.neverAllowScheduling) {
      val once: Cancellable = system.scheduler.scheduleOnce(initialDelay) {
        execute(f, taskName)
      }
      _cancellables :+= new NamedCancellable(once, taskName)
    } else log.info(s"permanently disable scheduling for task: $taskName")

  def scheduleTask(system: ActorSystem, initialDelay: FiniteDuration, frequency: FiniteDuration, receiver: ActorRef, message: Any, desc:Option[String] = None): Unit = {
    val taskName = desc getOrElse s"send message $message to actor $receiver"
    log.info(s"Scheduling $taskName")
    scheduleTask(system, initialDelay, frequency, taskName) { receiver ! message }
  }

  def cancelTasks() = {
    log.info(s"Cancelling scheduled (${_cancellables.length}) tasks: ${_cancellables.mkString(",")}")
    for (task <- _cancellables) {
      task.cancel()
      log.info(s"[cancelTask] task:${task.name}) isCancelled:${task.isCancelled}")
    }
  }

  override def onStop() {
    cancelTasks()
    super.onStop()
  }
}
