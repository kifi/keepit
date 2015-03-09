package com.keepit.common.plugin

import com.keepit.common.performance._
import com.keepit.common.logging.Logging
import com.keepit.common.service.ServiceStatus
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.actor.ActorInstance

import akka.actor.{ ActorSystem, Cancellable, ActorRef }
import play.api.Plugin

import us.theatr.akka.quartz._
import com.keepit.common.zookeeper.{ServiceInstance, ServiceDiscovery}
import scala.collection.mutable.ListBuffer

trait SchedulingProperties {
  def enabled: Boolean
  //bad name, can you think of anything else?
  //method returns true if schedualing is enabled and the instance is the leader
  def enabledOnlyForLeader: Boolean
  def enabledOnlyForOneMachine(taskName: String): Boolean
}

class SchedulingPropertiesImpl(serviceDiscovery: ServiceDiscovery, val enabled: Boolean = true) extends SchedulingProperties {
  def enabledOnlyForLeader: Boolean = enabled && serviceDiscovery.isLeader()
  def enabledOnlyForOneMachine(taskName: String): Boolean = enabled && isRunnerFor(taskName)

  private def isRunningFor(taskName: String, me: ServiceInstance, members: Vector[ServiceInstance]): Boolean = {
    val index = (taskName.hashCode() & 0x7FFFFFFF) % members.size
    members(index) == me
  }

  private def isRunningFor(members: Vector[ServiceInstance], me: ServiceInstance, taskName: String): Boolean = {
    val offline = members.filter(_.remoteService.status == ServiceStatus.OFFLINE)
    if (offline.isEmpty) {
      //if there's no offline service, consider all cluster
      isRunningFor(taskName, members)
    } else {
      if (thisInstance.exists(me => offline.contains(me))) {
        //if there's at least one offline service and I'm offline as well, use only offline services for the check
        isRunningFor(taskName, offline)
      } else {
        //if i'm not an offline services and at least one like this exist in my cluster, don't even consider me
        false
      }
    }
  }

  def isRunnerFor(taskName: String): Boolean = if (isCanary) false else zkClient.session { zk =>
    if (!stillRegistered()) {
      log.warn(s"service did not register itself yet!")
      return false
    }
    serviceDiscovery.thisInstance exists { me =>
      isRunningFor(serviceDiscovery.instancesInCluster, me, taskName)
    }
  }
}

case class NamedCancellable(underlying: Cancellable, taskName: String) extends Cancellable {
  def name() = taskName
  def cancel() = underlying.cancel
  def isCancelled: Boolean = underlying.isCancelled
  override def toString(): String = name()
}

trait SchedulerPlugin extends Plugin with Logging {

  def scheduling: SchedulingProperties

  val _cancellables: ListBuffer[NamedCancellable] = ListBuffer()

  private def scheduleTask(system: ActorSystem, initialDelay: FiniteDuration, frequency: FiniteDuration, taskName: String)(f: => Unit): Unit = _cancellables.synchronized {
    _cancellables +=
      NamedCancellable(system.scheduler.schedule(initialDelay, frequency) { f }, taskName)
  }

  /**
   * @param quartz you can inject an `quartz: ActorInstance[QuartzActor]` for that. this is the actor that manages the quartz schedualing
   * @param receiver this is the actor that will get the message
   * @param cron a string describing the cron schedule. see http://www.quartz-scheduler.org/documentation/quartz-1.x/tutorials/crontrigger for more info
   * @param message this is the message that would be sent to the reciever
   */
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

  /**
   * @see #cronTaskOnLeader
   */
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
    if (scheduling.enabled) {
      log.info(s"Scheduling $taskName")
      scheduleTask(system, initialDelay, frequency, taskName) {
        if (scheduling.enabledOnlyForLeader) {
          timing(s"executing scheduled task: $taskName") {
            receiver ! message
          }
        }
      }
    } else {
      log.debug(s"permanently disable scheduling for task: $taskName")
    }
  }

  def scheduleTaskOnOneMachine(system: ActorSystem, initialDelay: FiniteDuration, frequency: FiniteDuration, receiver: ActorRef, message: Any, name: String): Unit = {
    val taskName = s"[$name] send message $message to actor $receiver on one machine only"
    if (scheduling.enabled) {
      log.info(s"Scheduling $taskName")
      scheduleTask(system, initialDelay, frequency, taskName) {
        if (scheduling.enabledOnlyForOneMachine(name)) {
          timing(s"executing scheduled task: $taskName") {
            receiver ! message
          }
        }
      }
    } else {
      log.debug(s"permanently disable scheduling for task: $taskName")
    }
  }

  def scheduleTaskOnAllMachines(system: ActorSystem, initialDelay: FiniteDuration, frequency: FiniteDuration, receiver: ActorRef, message: Any): Unit = {
    val taskName = s"send message $message to actor $receiver on all machines"
    if (scheduling.enabled) {
      log.info(s"Scheduling $taskName")
      scheduleTask(system, initialDelay, frequency, taskName) {
        timing(s"executing scheduled task: $taskName") {
          receiver ! message
        }
      }
    } else {
      log.debug(s"permanently disable scheduling for task: $taskName")
    }
  }

  def scheduleTaskOnLeader(system: ActorSystem, initialDelay: FiniteDuration, frequency: FiniteDuration, name: String)(f: => Unit): Unit = {
    val taskName = s"[$name] call task function on leader only"
    if (scheduling.enabled) {
      log.info(s"Scheduling $taskName")
      scheduleTask(system, initialDelay, frequency, taskName) {
        if (scheduling.enabledOnlyForLeader) {
          timing(s"executing scheduled task: $taskName") {
            f
          }
        }
      }
    } else {
      log.debug(s"permanently disable scheduling for task: $taskName")
    }
  }

  def scheduleTaskOnOneMachine(system: ActorSystem, initialDelay: FiniteDuration, frequency: FiniteDuration, name: String)(f: => Unit): Unit = {
    val taskName = s"[$name] call task function on one machine only"
    if (scheduling.enabled) {
      log.info(s"Scheduling $taskName")
      scheduleTask(system, initialDelay, frequency, taskName) {
        if (scheduling.enabledOnlyForOneMachine(name)) {
          timing(s"executing scheduled task: $taskName") {
            f
          }
        }
      }
    } else {
      log.debug(s"permanently disable scheduling for task: $taskName")
    }
  }

  def scheduleTaskOnAllMachines(system: ActorSystem, initialDelay: FiniteDuration, frequency: FiniteDuration, name: String)(f: => Unit): Unit = {
    val taskName = s"[$name] call task function on all machines"
    if (scheduling.enabled) {
      log.info(s"Scheduling $taskName")
      scheduleTask(system, initialDelay, frequency, taskName) {
        timing(s"executing scheduled task: $taskName") {
          f
        }
      }
    } else {
      log.debug(s"permanently disable scheduling for task: $taskName")
    }
  }

  def cancelTasks() = _cancellables.synchronized {
    log.debug(s"Cancelling scheduled tasks: ${_cancellables map (_.name) mkString ","}")
    _cancellables foreach { task =>
      if (!task.isCancelled) {
        log.debug(s"[aboutToCancelTask] task:${task.name}) isCancelled:${task.isCancelled}")
        task.cancel()
        log.info(s"[canceledTask] task:${task.name}) isCancelled:${task.isCancelled}")
      } else {
        log.info(s"[canceledTask] task:${task.name}) is already cancelled")
      }
    }
  }

  override def onStop() {
    //cancelTasks()
    super.onStop()
  }
}
