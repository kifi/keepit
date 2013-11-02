package com.keepit.search.graph

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.Inject
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.common.actor.ActorInstance
import com.keepit.inject._
import play.api.Play.current
import scala.concurrent.Future
import scala.concurrent.duration._
import com.keepit.search.index.BackUp
import com.keepit.common.service.ServiceStatus
import com.keepit.common.zookeeper.ServiceDiscovery

case object Update

private[graph] class URIGraphActor @Inject() (
    airbrake: AirbrakeNotifier,
    uriGraph: URIGraph)
  extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case Update => try {
        sender ! uriGraph.update()
      } catch {
        case e: Exception =>
          airbrake.notify(AirbrakeError(exception = e,
              message = Some("Error updating uri graph")))
          sender ! -1
      }
    case BackUp => uriGraph.backup()
    case m => throw new UnsupportedActorMessage(m)
  }
}

trait URIGraphPlugin extends SchedulingPlugin {
  def update()
  def reindex()
  def reindexCollection()
}

class URIGraphPluginImpl @Inject() (
    actor: ActorInstance[URIGraphActor],
    uriGraph: URIGraph,
    serviceDiscovery: ServiceDiscovery)
  extends URIGraphPlugin with Logging {

  val schedulingProperties = SchedulingProperties.AlwaysEnabled
  implicit val actorTimeout = Timeout(5 seconds)

  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actor.system, 30 seconds, 1 minute, actor.ref, Update)
    log.info("starting URIGraphPluginImpl")
    serviceDiscovery.thisInstance.filter(_.remoteService.healthyStatus == ServiceStatus.BACKING_UP).foreach { _ =>
      scheduleTask(actor.system, 10 minutes, 3 hours, actor.ref, BackUp)
    }
  }
  override def onStop() {
    log.info("stopping URIGraphPluginImpl")
    cancelTasks()
    uriGraph.close()
  }

  override def update() {
    actor.ref ! Update
  }

  override def reindex() {
    uriGraph.reindex()
    actor.ref ! Update
  }

  override def reindexCollection() {
    uriGraph.reindexCollection()
    actor.ref ! Update
  }
}
