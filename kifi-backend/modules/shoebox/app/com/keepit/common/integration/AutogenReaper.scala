package com.keepit.common.integration

import com.keepit.model._
import scala.concurrent.duration._

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import play.api.{Play, Plugin}
import play.api.Play.current
import scala.collection.mutable
import com.keepit.common.db.Id

trait AutogenReaperPlugin extends Plugin {
  def reap()
}

class AutogenReaperPluginImpl @Inject() (
  actor: ActorInstance[AutogenReaper],
  val schedulingProperties: SchedulingProperties //only on leader
) extends Logging with AutogenReaperPlugin with SchedulingPlugin {

  log.info(s"<ctr> ReaperPlugin created")

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    for (app <- Play.maybeApplication) {
      val (initDelay, freq) = if (Play.isDev) (15 seconds, 15 seconds) else (5 minutes, 15 minutes)
      log.info(s"[onStart] ReaperPlugin started with initDelay=$initDelay freq=$freq")
      scheduleTask(actor.system, initDelay, freq, actor.ref, Reap, Some("AutogenReaper.Reap"))
    }
  }
  override def onStop() {
    log.info(s"[AutogenReaperPlugin] stopped")
    super.onStop
  }

  override def reap() { actor.ref ! Reap }
}

private[integration] case class Reap()

private[integration] class AutogenReaper @Inject() (
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
        implicit val userOrd = new Ordering[Id[User]] {
          def compare(x: Id[User], y: Id[User]): Int = x.id compare y.id
        }
        for (exp <- generated) {
          userSessionRepo.invalidateByUser(exp.userId)
          userExperimentRepo.getAllUserExperiments(exp.userId) foreach { exp =>
            userExperimentRepo.delete(exp)
          }
          for (emailAddr <- emailAddressRepo.getAllByUser(exp.userId)) {
            emailAddressRepo.delete(emailAddr)
          }
          for (sui <- socialUserInfoRepo.getByUser(exp.userId)) {
            socialUserInfoRepo.save(sui.withState(SocialUserInfoStates.INACTIVE)) // also not there yet
          }
          for (cred <- userCredRepo.findByUserIdOpt(exp.userId)) {
            userCredRepo.delete(cred)
          }
          val user = userRepo.get(exp.userId)
          log.info(s"[reap] processing $user")
          // todo: userRepo.delete(user) -- not there yet (bookmarks/keeps, etc.)
          userRepo.save(user.withState(UserStates.INACTIVE))
        }
        generated foreach { exp =>
          userExperimentRepo.getAllUserExperiments(exp.userId) foreach { exp =>
            userExperimentRepo.delete(exp)
          }
        }
      }
    case m => throw new UnsupportedActorMessage(m)
  }

}
