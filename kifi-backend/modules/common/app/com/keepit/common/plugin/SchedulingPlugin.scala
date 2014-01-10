package com.keepit.common.plugin

import com.keepit.common.performance._
import com.keepit.common.logging.Logging
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.actor.ActorInstance

import akka.actor.{ActorSystem, Cancellable, ActorRef}
import play.api.Plugin

import us.theatr.akka.quartz._
import com.keepit.common.zookeeper.ServiceDiscovery

trait SchedulingProperties {
  def enabled: Boolean
  //bad name, can you think of anything else?
  //method returns true if schedualing is enabled and the instance is the leader
  def enabledOnlyForLeader: Boolean
}

class SchedulingPropertiesImpl(serviceDiscovery: ServiceDiscovery, val enabled: Boolean = true) extends SchedulingProperties {
  def enabledOnlyForLeader: Boolean = enabled && serviceDiscovery.isLeader()
}

trait SchedulerPlugin extends Plugin with Logging {

  def scheduling: SchedulingProperties

  private var _cancellables: Seq[Cancellable] = Seq()

  private def scheduleTask(system: ActorSystem, initialDelay: FiniteDuration, frequency: FiniteDuration, taskName: String)(f: => Unit): Unit =
    _cancellables :+= system.scheduler.schedule(initialDelay, frequency) { f }

  def cronTaskOnLeader(quartz: ActorInstance[QuartzActor], receiver: ActorRef, cron: String, message: Any): Unit = {
    val taskName = s"cron message $message to actor $receiver on leader only"
    if (scheduling.enabled) {
      log.info(s"Scheduling $taskName in Cron")
      val spigot = new Spigot {
        def open = scheduling.enabledOnlyForLeader
      }
      quartz.ref ! AddCronSchedule(receiver, cron, message, false, spigot)
    } else log.info(s"permanently disable cron for task: $taskName")
  }

  def cronTaskOnAllMachines(quartz: ActorInstance[QuartzActor], receiver: ActorRef, cron: String, message: Any): Unit = {
    val taskName = s"cron message $message to actor $receiver on all machines"
    if (scheduling.enabled) {
      log.info(s"Scheduling $taskName in Cron")
      val spigot = new Spigot {
        def open = true
      }
      quartz.ref ! AddCronSchedule(receiver, cron, message, false, spigot)
    } else log.info(s"permanently disable cron for task: $taskName")
  }

  def scheduleTaskOnLeader(system: ActorSystem, initialDelay: FiniteDuration, frequency: FiniteDuration, receiver: ActorRef, message: Any): Unit = {
    val taskName = s"send message $message to actor $receiver on leader only"
    if(scheduling.enabled) {
      log.info(s"Scheduling $taskName on leader only")
      scheduleTask(system, initialDelay, frequency, taskName) {
        if (scheduling.enabledOnlyForLeader) {
          timing(s"executing scheduled task: $taskName") {
            receiver ! message
          }
        }
      }
    } else {
      log.info(s"permanently disable scheduling for task: $taskName")
    }
  }

  def scheduleTaskOnAllMachines(system: ActorSystem, initialDelay: FiniteDuration, frequency: FiniteDuration, receiver: ActorRef, message: Any): Unit = {
    val taskName = s"send message $message to actor $receiver on all machines"
    if(scheduling.enabled) {
      log.info(s"Scheduling $taskName on all machines")
      scheduleTask(system, initialDelay, frequency, taskName) {
        timing(s"executing scheduled task: $taskName") {
          receiver ! message
        }
      }
    } else {
      log.info(s"permanently disable scheduling for task: $taskName")
    }
  }

  def cancelTasks() = {
    log.info("Cancelling scheduled tasks")
    _cancellables.map(_.cancel())
  }

  override def onStop() {
    cancelTasks()
    super.onStop()
  }
}
