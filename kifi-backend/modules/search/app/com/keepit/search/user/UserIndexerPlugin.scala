package com.keepit.search.user

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.healthcheck.{AirbrakeError,AirbrakeNotifier}
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}

import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.util.Timeout.durationToTimeout
import com.keepit.common.service.ServiceStatus
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.index.BackUp

private[user] case object Update

private[user] class UserIndexerActor @Inject()(
  airbrake: AirbrakeNotifier,
  indexer: UserIndexer
) extends FortyTwoActor(airbrake) with Logging {

  def receive = {
    case Update => try {
      sender ! indexer.run
    } catch {
      case e: Exception =>
        airbrake.notify(AirbrakeError(exception = e, message = Some("Error updating user index")))
        sender ! -1
    }
    case BackUp => indexer.backup()
    case m => throw new UnsupportedActorMessage(m)
  }

}

trait UserIndexerPlugin extends SchedulingPlugin {
  def update(): Future[Int]
  def reindex(): Unit
}

class UserIndexerPluginImpl @Inject()(
   actor: ActorInstance[UserIndexerActor],
   indexer: UserIndexer,
  serviceDiscovery: ServiceDiscovery
) extends UserIndexerPlugin {

  val schedulingProperties = SchedulingProperties.AlwaysEnabled

  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actor.system, 30 seconds, 30 minute, actor.ref, Update)
    log.info("starting UserIndexerPluginImpl")
    serviceDiscovery.thisInstance.filter(_.remoteService.healthyStatus == ServiceStatus.BACKING_UP).foreach { _ =>
      scheduleTask(actor.system, 1 minute, 4 hours, actor.ref, BackUp)
    }
  }
  override def onStop() {
    log.info("stopping UserIndexerPluginImpl")
    cancelTasks()
    indexer.close()
  }

  override def update(): Future[Int] = actor.ref.ask(Update)(2 minutes).mapTo[Int]

  override def reindex() {
    indexer.reindex()
    actor.ref ! Update
  }
}
