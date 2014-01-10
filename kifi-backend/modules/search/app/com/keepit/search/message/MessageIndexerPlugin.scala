package com.keepit.search.message

import com.keepit.common.plugin.{SchedulingProperties, SchedulerPlugin}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorInstance
import com.keepit.search.article.BackUp
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceStatus

import com.google.inject.Inject

import akka.util.Timeout
import akka.pattern.ask


import scala.concurrent.Future
import scala.concurrent.duration._


case object UpdateIndex

class MessageIndexerActor @Inject() (
    airbrake: AirbrakeNotifier,
    messageIndexer: MessageIndexer)
  extends FortyTwoActor(airbrake) {

  def receive() = {
    case UpdateIndex => try {
        sender ! messageIndexer.update()
      } catch {
        case e: Exception =>
          airbrake.notify("Error updating message index", e)
          sender ! -1
      }
    case BackUp => messageIndexer.backup()
    case m => throw new UnsupportedActorMessage(m)
  }
}


trait MessageIndexerPlugin {
  def update(): Future[Int]
  def reindex(): Unit
}

class MessageIndexerPluginImpl @Inject() (
    actor: ActorInstance[MessageIndexerActor],
    messageIndexer: MessageIndexer,
    serviceDiscovery: ServiceDiscovery,
    val scheduling: SchedulingProperties)
  extends MessageIndexerPlugin with SchedulerPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  override def enabled: Boolean = true
  override def onStart() {
    scheduleTaskOnAllMachines(actor.system, 30 seconds, 1 minute, actor.ref, UpdateIndex)
    serviceDiscovery.thisInstance.filter(_.remoteService.healthyStatus == ServiceStatus.BACKING_UP).foreach { _ =>
      scheduleTaskOnAllMachines(actor.system, 15 minutes, 2 hours, actor.ref, BackUp)
    }
    log.info("starting MessageIndexerPluginImpl")
  }
  override def onStop() {
    log.info("stopping MessageIndexerPluginImpl")
    cancelTasks()
    messageIndexer.close()
  }

  override def update(): Future[Int] = actor.ref.ask(UpdateIndex)(1 minutes).mapTo[Int]

  override def reindex(): Unit = {
    messageIndexer.reindex()
    actor.ref ! UpdateIndex
  }
}
