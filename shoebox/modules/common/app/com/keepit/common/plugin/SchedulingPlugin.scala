package com.keepit.common.plugin

import com.keepit.common.zookeeper._
import com.keepit.common.logging.Logging
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.{ActorSystem, Cancellable, ActorRef}
import play.api.Plugin
import play.api.Mode

import com.google.inject.{Inject, Singleton, ImplementedBy}

sealed trait SchedulingEnabled

object SchedulingEnabled {
  case object Always extends SchedulingEnabled
  case object Never extends SchedulingEnabled
  case object LeaderOnly extends SchedulingEnabled
}

trait SchedulingPlugin extends Plugin with Logging {

  def schedulingProperties: SchedulingProperties

  private var _cancellables: Seq[Cancellable] = Seq()

  private def sendMessage(receiver: ActorRef, message: Any): Unit = if (schedulingProperties.allowScheduling) {
    log.info(s"sending a scheduled message $message to actor $receiver")
    receiver ! message
  }

  def scheduleTask(system: ActorSystem, initialDelay: FiniteDuration, frequency: FiniteDuration, receiver: ActorRef, message: Any) =
    if (!schedulingProperties.neverallowScheduling) {
      _cancellables :+= ( system.scheduler.schedule(initialDelay, frequency) { sendMessage(receiver, message) } )
    }

  def cancelTasks() = _cancellables.map(_.cancel)

  override def onStop() {
    cancelTasks()
    super.onStop()
  }
}
