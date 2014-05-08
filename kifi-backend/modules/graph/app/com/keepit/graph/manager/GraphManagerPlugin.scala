package com.keepit.graph.manager

import com.keepit.common.plugin.{SchedulingProperties, SchedulerPlugin}
import com.kifi.franz.SQSMessage
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{UnsupportedActorMessage, FortyTwoActor}
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.{Singleton, Inject}

sealed trait GraphManagerActorMessage
object GraphManagerActorMessage {
  case class UpdateGraph(maxBatchSize: Int, lockTimeout: FiniteDuration) extends GraphManagerActorMessage
  case class ProcessGraphUpdates(updates: Seq[SQSMessage[GraphUpdate]], maxBatchSize: Int, lockTimeout: FiniteDuration) extends GraphManagerActorMessage
  case object BackupGraph extends GraphManagerActorMessage
}

class GraphManagerActor @Inject() (
  graph: GraphManager,
  graphUpdateFetcher: GraphUpdateFetcher,
  airbrake: AirbrakeNotifier
) extends FortyTwoActor(airbrake) with Logging {
  private var updating = false
  import GraphManagerActorMessage._
  def receive = {
    case UpdateGraph(maxBatchSize, lockTimeout) => if (!updating) {
      graphUpdateFetcher.nextBatch(maxBatchSize, lockTimeout).map { updates =>
        log.info(s"${updates.length} graph updates were loaded from the queue.")
        self ! ProcessGraphUpdates(updates, maxBatchSize, lockTimeout)
      }
      updating = true
    }
    case ProcessGraphUpdates(updates, maxBatchSize, lockTimeout) => {
      if (updates.isEmpty) { graphUpdateFetcher.fetch(graph.state) }
      else {
        graph.update(updates.map(_.body): _*)
        updates.foreach(_.consume())
        log.info(s"${updates.length} graph updates were consumed from the queue.")
        self ! UpdateGraph(maxBatchSize, lockTimeout)
      }
      updating = false
    }
    case BackupGraph => graph.backup()
    case m => throw new UnsupportedActorMessage(m)
  }
}

@Singleton
class GraphManagerPlugin @Inject() (
  actor: ActorInstance[GraphManagerActor],
  val scheduling: SchedulingProperties
) extends SchedulerPlugin {
  import GraphManagerActorMessage._

  override def onStart() {
    log.info(s"starting $this")
    scheduleTaskOnAllMachines(actor.system, 2 minutes, 1 minutes, actor.ref, UpdateGraph(500, 5 minutes))
    scheduleTaskOnAllMachines(actor.system, 30 minutes, 2 hours, actor.ref, BackupGraph)
  }
}
