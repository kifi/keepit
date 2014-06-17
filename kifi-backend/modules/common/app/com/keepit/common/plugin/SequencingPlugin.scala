package com.keepit.common.plugin

import akka.util.Timeout
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{UnsupportedActorMessage, FortyTwoActor}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.util.RecurringTaskManager
import scala.concurrent.duration._

object SequencingPluginMessages {
  case object Process
}

trait SequencingPlugin extends SchedulerPlugin {

  val actor: ActorInstance[SequencingActor]

  val name: String = getClass.toString

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true

  override def onStart() {
    log.info(s"starting $name")
    scheduleTaskOnLeader(actor.system, 30 seconds, 5 seconds, actor.ref, SequencingPluginMessages.Process)
  }

  override def onStop() {
    log.info(s"stopping $name")
  }
}

trait SequenceAssigner extends RecurringTaskManager {
  private[this] val assignerClassName = this.getClass().getSimpleName()
  private[this] var reportedUnsupported = false

  val airbrake: AirbrakeNotifier

  def assignSequenceNumbers(): Unit

  override def doTask(): Unit = {
    try {
      assignSequenceNumbers()
    } catch {
      case e: UnsupportedOperationException =>
        if (!reportedUnsupported) {
          reportedUnsupported = true // report only once
          airbrake.notify(s"FATAL: deferred sequence assignment is not supported", e)
        }
        throw e
    }
  }
  override def onError(e: Throwable): Unit = { airbrake.notify(s"Error in $assignerClassName", e) }
}

abstract class SequencingActor(
  assigner: SequenceAssigner,
  airbrake: AirbrakeNotifier
) extends FortyTwoActor(airbrake) with Logging {

  import SequencingPluginMessages._

  def receive() = {
    case Process => assigner.request()
    case m => throw new UnsupportedActorMessage(m)
  }
}
