package com.keepit.search.graph

import akka.actor._
import akka.util.Timeout
import com.google.inject.Inject
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingProperties, SchedulerPlugin}
import com.keepit.common.actor.ActorInstance
import scala.concurrent.duration._
import com.keepit.search.index.IndexerPluginMessages._
import com.keepit.common.service.ServiceStatus
import com.keepit.common.zookeeper.ServiceDiscovery


private[graph] class URIGraphActor @Inject() (
    airbrake: AirbrakeNotifier,
    uriGraph: URIGraph)
  extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case UpdateIndex => try {
        sender ! uriGraph.update()
      } catch {
        case e: Exception =>
          airbrake.notify("Error updating uri graph", e)
          sender ! -1
      }
    case BackUpIndex => uriGraph.backup()
    case m => throw new UnsupportedActorMessage(m)
  }
}

trait URIGraphPlugin {
  def update()
  def reindex()
  def reindexCollection()
}

class URIGraphPluginImpl @Inject() (
    actor: ActorInstance[URIGraphActor],
    uriGraph: URIGraph,
    serviceDiscovery: ServiceDiscovery,
    val scheduling: SchedulingProperties)
  extends URIGraphPlugin with SchedulerPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  override def enabled: Boolean = true
  override def onStart() {
    scheduleTaskOnAllMachines(actor.system, 30 seconds, 1 minute, actor.ref, UpdateIndex)
    log.info("starting URIGraphPluginImpl")
    serviceDiscovery.thisInstance.filter(_.remoteService.healthyStatus == ServiceStatus.BACKING_UP).foreach { _ =>
      scheduleTaskOnAllMachines(actor.system, 10 minutes, 3 hours, actor.ref, BackUpIndex)
    }
  }
  override def onStop() {
    log.info("stopping URIGraphPluginImpl")
    cancelTasks()
    uriGraph.close()
  }

  override def update() {
    actor.ref ! UpdateIndex
  }

  override def reindex() {
    uriGraph.reindex()
    actor.ref ! UpdateIndex
  }

  override def reindexCollection() {
    uriGraph.reindexCollection()
    actor.ref ! UpdateIndex
  }
}
