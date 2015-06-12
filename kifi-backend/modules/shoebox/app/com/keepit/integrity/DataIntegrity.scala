package com.keepit.integrity

import com.keepit.common.logging.Logging
import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.actor.ActorInstance
import scala.concurrent.duration._
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }

trait DataIntegrityPlugin extends SchedulerPlugin

class DataIntegrityPluginImpl @Inject() (
  actor: ActorInstance[DataIntegrityActor],
  val scheduling: SchedulingProperties) //only on leader
    extends Logging with DataIntegrityPlugin {

  import DataIntegrityPlugin._

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTaskOnOneMachine(actor.system, 7 minutes, EVERY_N_MINUTE minutes, actor.ref, Cron, getClass.getSimpleName)
    scheduleTaskOnOneMachine(actor.system, 1 minutes, 1 minutes, actor.ref, LibrariesCheck, getClass.getSimpleName)
  }
}

private[integrity] case object CleanOrphans
private[integrity] case object Cron
private[integrity] case object SequenceNumberCheck
private[integrity] case object LibrariesCheck

private[integrity] class DataIntegrityActor @Inject() (
  airbrake: AirbrakeNotifier,
  orphanCleaner: OrphanCleaner,
  elizaSequenceNumberChecker: ElizaSequenceNumberChecker,
  libraryChecker: LibraryChecker)
    extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case CleanOrphans =>
      orphanCleaner.clean(false)
    case SequenceNumberCheck =>
      elizaSequenceNumberChecker.check()
    case LibrariesCheck =>
      libraryChecker.check()
    case Cron =>
      self ! CleanOrphans
      self ! SequenceNumberCheck
    case m => throw new UnsupportedActorMessage(m)
  }
}

object DataIntegrityPlugin {
  val EVERY_N_MINUTE = 10
}
