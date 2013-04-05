package com.keepit.common.plugin

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.{ActorSystem, Cancellable, ActorRef}
import play.api.Play.current
import play.api.Plugin
import play.api.Mode

trait SchedulingPlugin extends Plugin {
  final protected lazy val schedulerIsEnabled: Boolean =
    current.mode != Mode.Test && current.configuration.getBoolean("scheduler.enabled").getOrElse(true)

  private var _cancellables: Seq[Cancellable] = Seq()

  def scheduleTask(system: ActorSystem, initialDelay: FiniteDuration,
      frequency: FiniteDuration, receiver: ActorRef, message: Any) {
    if (schedulerIsEnabled)
      _cancellables :+= system.scheduler.schedule(initialDelay, frequency, receiver, message)
  }

  def cancelTasks() {
    _cancellables.map(_.cancel)
  }

  override def onStop() {
    cancelTasks()
    super.onStop()
  }
}
