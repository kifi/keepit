package com.keepit.integrity

import com.keepit.common.logging.Logging
import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.actor.ActorInstance
import scala.concurrent.duration._
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.payments.{ RewardsChecker, PaymentsIntegrityChecker }

trait DataIntegrityPlugin extends SchedulerPlugin

class DataIntegrityPluginImpl @Inject() (
  actor: ActorInstance[DataIntegrityActor],
  val scheduling: SchedulingProperties) //only on leader
    extends Logging with DataIntegrityPlugin {

  import DataIntegrityPlugin._

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() { //kill
    //    scheduleTaskOnOneMachine(actor.system, 7 minutes, EVERY_N_MINUTE minutes, actor.ref, Cron, getClass.getSimpleName)
    //    scheduleTaskOnOneMachine(actor.system, 7 minutes, EVERY_N_MINUTE minutes, actor.ref, SystemLibraryCheck, getClass.getSimpleName)
    //    scheduleTaskOnOneMachine(actor.system, 1 minutes, 1 minutes, actor.ref, LibrariesCheck, getClass.getSimpleName)
    //    scheduleTaskOnOneMachine(actor.system, 10 minutes, 5 hours, actor.ref, PaymentsCheck, getClass.getSimpleName)
    //    scheduleTaskOnOneMachine(actor.system, 1 minutes, 30 seconds, actor.ref, KeepsCheck, getClass.getSimpleName)
    //    scheduleTaskOnOneMachine(actor.system, 5 minutes, 5 minutes, actor.ref, OrganizationsCheck, getClass.getSimpleName)
    //    scheduleTaskOnOneMachine(actor.system, 10 minutes, 2 hours, actor.ref, RewardsCheck, getClass.getSimpleName)
  }
}

private[integrity] case object CleanOrphans
private[integrity] case object Cron
private[integrity] case object SequenceNumberCheck
private[integrity] case object LibrariesCheck
private[integrity] case object KeepsCheck
private[integrity] case object OrganizationsCheck
private[integrity] case object SystemLibraryCheck
private[integrity] case object PaymentsCheck
private[integrity] case object RewardsCheck

private[integrity] class DataIntegrityActor @Inject() (
    airbrake: AirbrakeNotifier,
    orphanCleaner: OrphanCleaner,
    elizaSequenceNumberChecker: ElizaSequenceNumberChecker,
    libraryChecker: LibraryChecker,
    paymentsChecker: PaymentsIntegrityChecker,
    rewardsChecker: RewardsChecker,
    keepChecker: KeepChecker,
    organizationChecker: OrganizationChecker) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case CleanOrphans =>
      orphanCleaner.clean(false)
    case SequenceNumberCheck =>
      elizaSequenceNumberChecker.check()
    case LibrariesCheck =>
      libraryChecker.check()
    case KeepsCheck =>
      keepChecker.check()
    case OrganizationsCheck =>
      organizationChecker.check()
    case SystemLibraryCheck =>
      libraryChecker.checkSystemLibraries()
    case PaymentsCheck =>
      paymentsChecker.checkAccounts()
    case RewardsCheck =>
      rewardsChecker.checkAccounts()
    case Cron =>
      self ! CleanOrphans
      self ! SequenceNumberCheck
    case m => throw new UnsupportedActorMessage(m)
  }
}

object DataIntegrityPlugin {
  val EVERY_N_MINUTE = 10
}
