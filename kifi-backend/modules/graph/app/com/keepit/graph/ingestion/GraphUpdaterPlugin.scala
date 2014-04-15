package com.keepit.graph.ingestion

import com.keepit.graph.model._
import com.keepit.common.plugin.SchedulerPlugin
import com.kifi.franz.SQSMessage
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{UnsupportedActorMessage, FortyTwoActor}
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.service.ServiceStatus
import scala.concurrent.duration._
import com.keepit.common.zookeeper.ServiceDiscovery
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait GraphUpdater[G <: GraphManager] {
  val state: GraphUpdaterState
  val graphDirectory: GraphDirectory[G]
  val graph: G
  val processUpdate: GraphUpdateProcessor

  def process(updates: Seq[SQSMessage[GraphUpdate]]): Unit = {
    val relevantUpdates = updates.collect { case SQSMessage(update, _, _) if update.seq > state.getCurrentSequenceNumber(update.kind) => update }
    graph.write { implicit writer => relevantUpdates.sortBy(_.seq.value).foreach(processUpdate(_)) }
    state.updateWith(relevantUpdates) // todo(Léo): add transaction callback capabilities to GraphWriter (cf SessionWrapper)
    updates.foreach(_.consume)
  }

  def backup(): Unit = {
    graph.synchronized {
      graphDirectory.persistGraph(graph)
      graphDirectory.persistState(state)
    }
    graphDirectory.synchronized {
      graphDirectory.doBackup()
    }
  }
}

sealed trait GraphUpdaterMessage
object GraphUpdaterMessage {
  case class UpdateGraph(maxBatchSize: Int) extends GraphUpdaterMessage
  case class ProcessGraphUpdates(updates: Seq[SQSMessage[GraphUpdate]], maxBatchSize: Int) extends GraphUpdaterMessage
  case object BackupGraph extends GraphUpdaterMessage
}

class GraphUpdaterActor[G <: GraphManager](
  airbrake: AirbrakeNotifier,
  graphUpdater: GraphUpdater[G],
  graphUpdateFetcher: GraphUpdateFetcher
) extends FortyTwoActor(airbrake) with Logging {
  private var updating = false
  import GraphUpdaterMessage._
  def receive = {
    case UpdateGraph(maxBatchSize) => if (!updating) {
      graphUpdateFetcher.nextBatch(maxBatchSize).map { updates =>
        self ! ProcessGraphUpdates(updates, maxBatchSize)
      }
      updating = true
    }
    case ProcessGraphUpdates(updates, maxBatchSize) => {
      graphUpdater.process(updates)
      if (updates.length < maxBatchSize) { graphUpdateFetcher.fetch(graphUpdater.state) }
      updating = false
    }
    case BackupGraph => graphUpdater.backup()
    case m => throw new UnsupportedActorMessage(m)
  }
}

abstract class GraphUpdaterPlugin[G <: GraphManager](
  actor: ActorInstance[GraphUpdaterActor[G]],
  serviceDiscovery: ServiceDiscovery
) extends SchedulerPlugin {
  import GraphUpdaterMessage._

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
