package com.keepit.common.plugin

import akka.util.Timeout
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.util.RecurringTaskManager
import scala.concurrent.duration._

object SequencingPluginMessages {
  case object Process
  case object SanityCheck
}

trait SequencingPlugin extends SchedulerPlugin {

  val actor: ActorInstance[SequencingActor]

  val name: String = getClass.toString

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true

  val interval: FiniteDuration = 5 seconds
  val sanityCheckInterval: FiniteDuration = 10 minutes

  override def onStart() { //keep me alive!
    scheduleTaskOnLeader(actor.system, 30 seconds, interval, actor.ref, SequencingPluginMessages.Process)
    scheduleTaskOnAllMachines(actor.system, 100 seconds, sanityCheckInterval, actor.ref, SequencingPluginMessages.SanityCheck)
  }
}

trait SequenceAssigner extends RecurringTaskManager {

  def assignSequenceNumbers(): Unit
  def sanityCheck(): Unit

  override def doTask(): Unit = assignSequenceNumbers()
}

class SequenceNumberAssignmentStalling(name: String, seq: Long) extends Exception(s"sequence number assignment may be stalling on $name: $seq")

abstract class SequencingActor(
    assigner: SequenceAssigner,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  import SequencingPluginMessages._

  def receive() = {
    case Process => assigner.request()
    case SanityCheck => assigner.sanityCheck()
    case m => throw new UnsupportedActorMessage(m)
  }
}
