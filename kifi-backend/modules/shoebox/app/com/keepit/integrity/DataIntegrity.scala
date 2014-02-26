package com.keepit.integrity

import com.keepit.common.logging.Logging
import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.actor.ActorInstance
import scala.concurrent.duration._
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}


trait DataIntegrityPlugin extends SchedulerPlugin

class DataIntegrityPluginImpl @Inject() (
    actor: ActorInstance[DataIntegrityActor],
    val scheduling: SchedulingProperties) //only on leader
  extends Logging with DataIntegrityPlugin {

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    scheduleTaskOnLeader(actor.system, 5 minutes, 1 hour, actor.ref, Cron)
  }
}

private[integrity] case object CleanOrphans
private[integrity] case object Cron
private[integrity] case object SequenceNumberCheck

private[integrity] class DataIntegrityActor @Inject() (
    airbrake: AirbrakeNotifier,
    orphanCleaner: OrphanCleaner,
    elizaSequenceNumberChecker: ElizaSequenceNumberChecker)
  extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case CleanOrphans =>
      orphanCleaner.clean(false)
    case SequenceNumberCheck =>
      elizaSequenceNumberChecker.check()
    case Cron =>
      self ! CleanOrphans
      self ! SequenceNumberCheck
    case m => throw new UnsupportedActorMessage(m)
  }
}
