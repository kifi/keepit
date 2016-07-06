package com.keepit.graph.manager

import com.keepit.common.plugin.{ SchedulingProperties, SchedulerPlugin }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.{ Singleton, Inject }
import scala.util.{ Try, Failure, Success }

sealed trait GraphManagerActorMessage
object GraphManagerActorMessage {
  case class UpdateGraph(customFetchSizes: Map[GraphUpdateKind[_ <: GraphUpdate], Int], defaultFetchSize: Int) extends GraphManagerActorMessage
  case class ProcessGraphUpdates[U <: GraphUpdate](updates: Seq[U], kind: GraphUpdateKind[U], fetchSize: Int) extends GraphManagerActorMessage
  case object BackupGraph extends GraphManagerActorMessage
  case class CancelUpdate[U <: GraphUpdate](kind: GraphUpdateKind[U])
}

class GraphManagerActor @Inject() (
    graph: GraphManager,
    graphUpdateFetcher: GraphUpdateFetcher,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {
  import GraphManagerActorMessage._

  private var updating = Set[GraphUpdateKind[_ <: GraphUpdate]]()

  private def fetch[U <: GraphUpdate](kind: GraphUpdateKind[U], fetchSize: Int): Unit = {
    val seq = graph.state.getCurrentSequenceNumber(kind)
    graphUpdateFetcher.fetch(kind, seq, fetchSize).onComplete {
      case Success(updates) => self ! ProcessGraphUpdates(updates, kind, fetchSize)
      case Failure(_) => self ! CancelUpdate(kind)
    }
  }

  def receive = {
    case UpdateGraph(customFetchSizes, defaultFetchSize) => (GraphUpdateKind.toBeIngested -- updating).foreach { kind =>
      val fetchSize = customFetchSizes.getOrElse(kind, defaultFetchSize)
      fetch(kind, fetchSize)
      updating += kind
    }

    case ProcessGraphUpdates(updates, kind, fetchSize) => {
      Try { if (updates.nonEmpty) { graph.update(updates: _*) } } match {
        case Failure(ex) => {
          log.error(s"Could not process graph updates: $ex")
          airbrake.notify("Could not process graph updates", ex)
          updating -= kind
        }
        case Success(_) => {
          log.info(s"${updates.length} ${kind}s were ingested.")
          if (updates.length < fetchSize) { updating -= kind }
          else { fetch(kind, fetchSize) }
        }
      }
    }

    case CancelUpdate(kind) => updating -= kind

    case BackupGraph => graph.backup()

    case m => throw new UnsupportedActorMessage(m)
  }
}

@Singleton
class GraphManagerPlugin @Inject() (
    actor: ActorInstance[GraphManagerActor],
    val scheduling: SchedulingProperties) extends SchedulerPlugin {
  import GraphManagerActorMessage._

  override def onStart() {
//    scheduleTaskOnAllMachines(actor.system, 2 minutes, 1 minutes, actor.ref, UpdateGraph(Map(), 100))
//    scheduleTaskOnAllMachines(actor.system, 20 minutes, 2 hours, actor.ref, BackupGraph)
  }
}
