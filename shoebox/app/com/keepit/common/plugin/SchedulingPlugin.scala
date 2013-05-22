package com.keepit.common.plugin

import com.keepit.common.zookeeper._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.{ActorSystem, Cancellable, ActorRef}
import play.api.Plugin
import play.api.Mode

import com.google.inject.Inject

sealed trait SchedulingEnabled

object SchedulingEnabled {
  case object Always extends SchedulingEnabled
  case object Never extends SchedulingEnabled
  case object LeaderOnly extends SchedulingEnabled
}

class SchedulingProperties @Inject() (serviceDiscovery: ServiceDiscovery, schedulingEnabled: SchedulingEnabled) {
  def allowSchecualing = schedulingEnabled match {
    case SchedulingEnabled.Always => true
    case SchedulingEnabled.Never => false
    case SchedulingEnabled.LeaderOnly => serviceDiscovery.isLeader()
  }
}

trait SchedulingPlugin extends Plugin {

  def schedulingProperties: SchedulingProperties

  private var _cancellables: Seq[Cancellable] = Seq()

  private def sendMessage(receiver: ActorRef, message: Any): Unit = if (schedulingProperties.allowSchecualing) receiver ! message

  def scheduleTask(system: ActorSystem, initialDelay: FiniteDuration,
      frequency: FiniteDuration, receiver: ActorRef, message: Any) {
    _cancellables :+= ( system.scheduler.schedule(initialDelay, frequency) { sendMessage(receiver, message) } )
  }

  def cancelTasks() {
    _cancellables.map(_.cancel)
  }

  override def onStop() {
    cancelTasks()
    super.onStop()
  }
}
