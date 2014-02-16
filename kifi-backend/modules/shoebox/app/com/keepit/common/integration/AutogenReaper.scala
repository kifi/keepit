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
import com.keepit.common.db.Id
import com.keepit.common.time._

trait AutogenReaperPlugin extends Plugin {
  def reap()
}

class AutogenReaperPluginImpl @Inject() (
  actor: ActorInstance[AutogenReaper],
  val scheduling: SchedulingProperties //only on leader
) extends Logging with AutogenReaperPlugin with SchedulerPlugin {

  log.info(s"<ctr> ReaperPlugin created")

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    for (app <- Play.maybeApplication) {
      val (initDelay, freq) = if (Play.isDev) (15 seconds, 15 seconds) else (5 minutes, 15 minutes) // todo: inject
      log.info(s"[onStart] ReaperPlugin started with initDelay=$initDelay freq=$freq")
      scheduleTaskOnLeader(actor.system, initDelay, freq, actor.ref, Reap)
    }
  }
  override def onStop() {
    log.info(s"[AutogenReaperPlugin] stopped")
    super.onStop
  }

  override def reap() { actor.ref ! Reap }
}

private[integration] case class Reap()

case class AutogenReaperConf(deleteSocialUserInfo: Boolean, deleteUser: Boolean)

private[integration] class AutogenReaper @Inject() (
  db: Database,
  userExperimentRepo: UserExperimentRepo,
  userRepo: UserRepo,
  userCredRepo: UserCredRepo,
  userSessionRepo: UserSessionRepo,
  invitationRepo: InvitationRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  emailAddressRepo: EmailAddressRepo,
  bookmarkRepo: BookmarkRepo,
  collectionRepo: CollectionRepo,
  k2cRepo: KeepToCollectionRepo,
  airbrake: AirbrakeNotifier,
  autogenReaperConf: AutogenReaperConf
) extends FortyTwoActor(airbrake) with Logging {

  val deleteSocialUserInfo = autogenReaperConf.deleteSocialUserInfo
  val deleteUser = autogenReaperConf.deleteUser

  log.info(s"[AutogenReaper.ctr] deleteSUI=$deleteSocialUserInfo deleteUser=$deleteUser")

  def receive() = {
    case Reap =>
      for (threshold <- Play.maybeApplication map { app => // todo: inject
        if (Play.isDev) currentDateTime.minusSeconds(15) else currentDateTime.minusMinutes(15)
      }) {
        db.readWrite { implicit rw =>
          // a variant of this could live in UserCommander
          val generated = userExperimentRepo.getByType(ExperimentType.AUTO_GEN)
          val dues = generated filter (e => e.updatedAt.isBefore(threshold))
          log.info(s"[reap] total=(${generated.length}):[${generated.mkString(",")}]; due=(${dues.length}):[${dues.map(_.id.get).mkString(",")}]")
          implicit val userOrd = new Ordering[Id[User]] {
            def compare(x: Id[User], y: Id[User]): Int = x.id compare y.id
          }
          for (exp <- dues) {
            userSessionRepo.invalidateByUser(exp.userId)
            userExperimentRepo.getAllUserExperiments(exp.userId) foreach { exp =>
              exp.experimentType match {
                case ExperimentType.AUTO_GEN => userExperimentRepo.save(exp.withState(UserExperimentStates.INACTIVE))
                case _ => userExperimentRepo.delete(exp)
              }
            }
            for (emailAddr <- emailAddressRepo.getAllByUser(exp.userId)) {
              emailAddressRepo.delete(emailAddr)
            }
            for (sui <- socialUserInfoRepo.getByUser(exp.userId)) {
              for (invite <- invitationRepo.getByRecipientSocialUserId(sui.id.get)) {
                invitationRepo.delete(invite)
              }
              if (deleteSocialUserInfo) {
                log.info(s"[reap] DELETE sui=$sui")
                socialUserInfoRepo.delete(sui)
              } else {
                log.info(s"[reap] DEACTIVATE sui=$sui")
                socialUserInfoRepo.save(sui.withState(SocialUserInfoStates.INACTIVE))
              }
            }
            for (cred <- userCredRepo.findByUserIdOpt(exp.userId)) {
              userCredRepo.delete(cred)
            }
            val user = userRepo.get(exp.userId)
            log.info(s"[reap] processing $user")
            // bookmarks, collections & k2c
            for (bookmark <- bookmarkRepo.getByUser(exp.userId)) {
              bookmarkRepo.save(bookmark.withActive(false))
            }
            for (collection <- collectionRepo.getByUser(exp.userId)) {
              for (k2c <- k2cRepo.getByCollection(collection.id.get)) {
                k2cRepo.save(k2c.inactivate)
              }
              collectionRepo.save(collection.copy(state = CollectionStates.INACTIVE))
              collectionRepo.collectionChanged(collection.id.get)
            }
            userRepo.save(user.withState(UserStates.INACTIVE))
          }
          dues foreach { exp =>
            userExperimentRepo.getAllUserExperiments(exp.userId) foreach { exp =>
              userExperimentRepo.delete(exp)
            }
          }
        }
      }
    case m => throw new UnsupportedActorMessage(m)
  }

}
