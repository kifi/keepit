package com.keepit.common.plugin

import akka.util.Timeout
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{UnsupportedActorMessage, FortyTwoActor}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.util.RecurringTaskManager
import scala.concurrent.duration._

object DbSequencingPluginMessages {
  case object Process
}

trait DbSequencingPlugin[M] extends SchedulerPlugin {

  val actor: ActorInstance[DbSequencingActor[M]]

  val name: String = getClass.toString

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true

  override def onStart() {
    log.info(s"starting $name")
    scheduleTaskOnLeader(actor.system, 30 seconds, 5 seconds, actor.ref, DbSequencingPluginMessages.Process)
  }

  override def onStop() {
    log.info(s"stopping $name")
  }
}

abstract class DbSequenceAssigner[M](airbrake: AirbrakeNotifier) extends RecurringTaskManager {
  private[this] val assignerClassName = this.getClass().getSimpleName()
  private[this] var reportedUnsupported = false

  override def doTask(): Unit = {
    try {
      while (assignSequenceNumbers(20) > 0) {}
    } catch {
      case e: UnsupportedOperationException =>
        if (!reportedUnsupported) {
          reportedUnsupported = true
          airbrake.notify(s"Deferred sequence assignment is not supported", e)
        }
        throw e
    }
  }
  override def onError(e: Throwable): Unit = { airbrake.notify(s"Error in $assignerClassName", e) }

  protected def assignSequenceNumbers(limit: Int): Int
}

abstract class DbSequencingActor[M](
  assigner: DbSequenceAssigner[M],
  airbrake: AirbrakeNotifier
) extends FortyTwoActor(airbrake) with Logging {

  import DbSequencingPluginMessages._

  def receive() = {
    case Process => assigner.request()
    case m => throw new UnsupportedActorMessage(m)
  }
}
