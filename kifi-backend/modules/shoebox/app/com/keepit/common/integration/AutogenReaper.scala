package com.keepit.common.integration

import com.keepit.model._
import scala.concurrent.duration._

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}
import play.api.{Play, Plugin}
import play.api.Play.current

trait AutogenReaperPlugin {
  def reap()
}

class AutogenReaperPluginImpl @Inject() (
  actor: ActorInstance[AutogenAcctReaperActor],
  val scheduling: SchedulingProperties //only on leader
    ) extends Logging with AutogenReaperPlugin with SchedulerPlugin {

  log.info(s"<ctr> ReaperPlugin created")

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    for (app <- Play.maybeApplication) {
      val (initDelay, freq) = if (Play.isDev) (15 seconds, 15 seconds) else (5 minutes, 1 hour)
      log.info(s"[onStart] ReaperPlugin started with initDelay=$initDelay freq=$freq")
      scheduleTaskOnLeader(actor.system, initDelay, freq, actor.ref, Reap)
    }
  }

  override def reap() { actor.ref ! Reap }
}

private[integration] case class Reap()

private[integration] class AutogenAcctReaperActor @Inject() (
  db: Database,
  userExperimentRepo: UserExperimentRepo,
  userRepo: UserRepo,
  userCredRepo: UserCredRepo,
  userSessionRepo: UserSessionRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  emailAddressRepo: EmailAddressRepo,
  airbrake: AirbrakeNotifier
) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case Reap =>
      db.readWrite { implicit rw =>
        // a variant of this could live in UserCommander
        val generated = userExperimentRepo.getByType(ExperimentType.AUTO_GEN)
        log.info(s"[reap] got (${generated.length}) to be reaped: ${generated.mkString(",")}")
        generated foreach { exp =>
          val user = userRepo.get(exp.userId)
          log.info(s"[reap] processing $user")
          userSessionRepo.invalidateByUser(exp.userId)
          userRepo.save(user.withState(UserStates.INACTIVE)) // mark as inactive for now; delete later
          socialUserInfoRepo.getByUser(exp.userId) map { sui =>
            socialUserInfoRepo.save(sui.withState(SocialUserInfoStates.INACTIVE))
          }
          emailAddressRepo.getAllByUser(exp.userId) foreach { emailAddr =>
            emailAddressRepo.save(emailAddr.withState(EmailAddressStates.INACTIVE))
          }
          // skip UserCred/UserExp for now; delete later
        }
        userExperimentRepo.getByType(ExperimentType.AUTO_GEN) foreach { exp => // mark as inactive for now; delete later
          userExperimentRepo.save(exp.withState(UserExperimentStates.INACTIVE))
        }
      }
    case m => throw new UnsupportedActorMessage(m)
  }

}
