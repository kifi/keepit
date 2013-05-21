package com.keepit.common.plugin

import com.keepit.common.zookeeper._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.{ActorSystem, Cancellable, ActorRef}
import play.api.Plugin
import play.api.Mode

sealed trait SchedulingEnabled

object SchedulingEnabled {
  case object Always extends SchedulingEnabled
  case object Never extends SchedulingEnabled
  case object LeaderOnly extends SchedulingEnabled
}

clsss SchedulingProperties @Inject() (
  serviceDiscovery: ServiceDiscovery
  schedulingEnabled: SchedulingEnabled) {

}

trait SchedulingPlugin extends Plugin {

  def serviceDiscovery: ServiceDiscovery
  def schedulingEnabled: SchedulingEnabled

  private def allowSchecualing = schedulingEnabled match {
    case SchedulingEnabled.Always => true
    case SchedulingEnabled.Never => false
    case SchedulingEnabled.LeaderOnly => serviceDiscovery.isLeader()
  }

  private var _cancellables: Seq[Cancellable] = Seq()

  def scheduleTask(system: ActorSystem, initialDelay: FiniteDuration,
      frequency: FiniteDuration, receiver: ActorRef, message: Any) {
    if (allowSchecualing) _cancellables :+= system.scheduler.schedule(initialDelay, frequency, receiver, message)
  }

  def cancelTasks() {
    _cancellables.map(_.cancel)
  }

  override def onStop() {
    cancelTasks()
    super.onStop()
  }
}
