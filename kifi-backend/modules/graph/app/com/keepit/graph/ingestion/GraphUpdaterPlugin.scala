package com.keepit.graph.ingestion

import com.keepit.common.plugin.{SchedulingProperties, SchedulerPlugin}
import com.kifi.franz.SQSMessage
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{UnsupportedActorMessage, FortyTwoActor}
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.service.ServiceStatus
import scala.concurrent.duration._
import com.keepit.common.zookeeper.ServiceDiscovery
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.graph.GraphManager

sealed trait GraphUpdaterActorMessage
object GraphUpdaterActorMessage {
  case class UpdateGraph(maxBatchSize: Int) extends GraphUpdaterActorMessage
  case class ProcessGraphUpdates(updates: Seq[SQSMessage[GraphUpdate]], maxBatchSize: Int) extends GraphUpdaterActorMessage
  case object BackupGraph extends GraphUpdaterActorMessage
}

class GraphUpdaterActor(
  graph: GraphManager,
  graphUpdateFetcher: GraphUpdateFetcher,
  airbrake: AirbrakeNotifier
) extends FortyTwoActor(airbrake) with Logging {
  private var updating = false
  import GraphUpdaterActorMessage._
  def receive = {
    case UpdateGraph(maxBatchSize) => if (!updating) {
      graphUpdateFetcher.nextBatch(maxBatchSize).map { updates =>
        self ! ProcessGraphUpdates(updates, maxBatchSize)
      }
      updating = true
    }
    case ProcessGraphUpdates(updates, maxBatchSize) => {
      val state = graph.update(updates.map(_.body): _*)
      updates.foreach(_.consume)
      updating = false
      if (updates.length < maxBatchSize) { graphUpdateFetcher.fetch(state) }
      else { self ! UpdateGraph(maxBatchSize) }
    }
    case BackupGraph => graph.backup()
    case m => throw new UnsupportedActorMessage(m)
  }
}

class GraphUpdaterPlugin(
  actor: ActorInstance[GraphUpdaterActor],
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends SchedulerPlugin {
  import GraphUpdaterActorMessage._

  override def onStart() {
    log.info(s"starting $this")
    scheduleTaskOnAllMachines(actor.system, 30 seconds, 1 minutes, actor.ref, UpdateGraph)

    serviceDiscovery.thisInstance.filter(_.remoteService.healthyStatus == ServiceStatus.BACKING_UP) match {
      case Some(_) => // graph backup instance
        scheduleTaskOnAllMachines(actor.system, 30 minutes, 2 hours, actor.ref, BackupGraph)
      case None => // regular graph instance
    }
  }
}
