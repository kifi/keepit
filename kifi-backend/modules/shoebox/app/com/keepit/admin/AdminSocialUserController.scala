package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.abook.ABookServiceClient
import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.model._
import com.keepit.social.{ SocialGraphPlugin, SocialUserRawInfoStore }
import org.joda.time.DateTimeZone
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import views.html

import scala.util.Random

class AdminSocialUserController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  socialConnectionRepo: SocialConnectionRepo,
  socialUserRawInfoStore: SocialUserRawInfoStore,
  socialGraphPlugin: SocialGraphPlugin,
  clock: Clock)
    extends AdminUserActions {

  def migrateHashColumn(page: Int, iters: Int) = AdminUserPage { implicit request =>
    val pages = (0 until iters).map { i => page + i }
    db.readWriteBatch(pages) {
      case (session, p) =>
        implicit val s = session
        val socialUsers = socialUserInfoRepo.page(p, 1000)
        socialUsers.foreach(socialUserInfoRepo.doNotUseSave)
        if (p % 10 == 0) {
          log.info(s"[migrateHashColumn] Page $p done")
        }
    }
    Ok(JsNumber(pages.last))
  }

  def resetSocialUser(socialUserId: Id[SocialUserInfo]) = AdminUserPage { implicit request =>
    val socialUserInfo = db.readWrite { implicit s =>
      socialUserInfoRepo.save(socialUserInfoRepo.get(socialUserId).reset())
    }
    Redirect(com.keepit.controllers.admin.routes.AdminSocialUserController.socialUserView(socialUserInfo.id.get))
  }

  def socialUserView(socialUserId: Id[SocialUserInfo]) = AdminUserPage.async { implicit request =>
    for {
      socialUserInfo <- db.readOnlyReplicaAsync { implicit s => socialUserInfoRepo.get(socialUserId) }
      socialConnections <- db.readOnlyReplicaAsync { implicit s => socialConnectionRepo.getSocialConnectionInfos(socialUserInfo.id.get).sortWith((a, b) => a.fullName < b.fullName) }
    } yield {
      val rawInfo = socialUserRawInfoStore.syncGet(socialUserInfo.id.get)
      Ok(html.admin.socialUser(socialUserInfo, socialConnections, rawInfo))
    }
  }

  def disconnectSocialUser(suiId: Id[SocialUserInfo], revoke: Boolean = false) = AdminUserPage { implicit request =>
    val sui = db.readOnlyMaster(socialUserInfoRepo.get(suiId)(_))
    if (revoke) {
      socialGraphPlugin.asyncRevokePermissions(sui)
    }
    db.readWrite { implicit s =>
      socialConnectionRepo.deactivateAllConnections(sui.id.get)
      socialUserInfoRepo.invalidateCache(sui)
      socialUserInfoRepo.save(sui.copy(credentials = None, userId = None))
      socialUserInfoRepo.getByUser(request.userId).map(socialUserInfoRepo.invalidateCache)
    }
    Ok
  }

  def refreshSocialInfo(socialUserInfoId: Id[SocialUserInfo]) = AdminUserPage { implicit request =>
    val socialUserInfo = db.readOnlyMaster { implicit s => socialUserInfoRepo.get(socialUserInfoId) }
    if (socialUserInfo.credentials.isEmpty) throw new Exception("can't fetch user info for user with missing credentials: %s".format(socialUserInfo))
    socialGraphPlugin.asyncFetch(socialUserInfo)
    Redirect(com.keepit.controllers.admin.routes.AdminSocialUserController.socialUserView(socialUserInfoId))
  }

  // randomizes that last_graph_refresh datetime for all users between now and X minutes ago
  def smoothLastGraphRefreshTimes(minutesFromNow: Int) = AdminUserAction { implicit request =>
    Ok {
      val now = clock.now()(DateTimeZone.UTC)
      val socialIdsByNetwork = {
        val infos = db.readOnlyReplica { implicit s => socialUserInfoRepo.getAllUsersToRefresh() }
        infos.groupBy(_.networkType).mapValues(_.map(_.socialId).toSet).toMap
      }

      socialIdsByNetwork.foreach {
        case (networkType, socialIds) =>
          socialIds.grouped(10).foreach { idGroup =>
            db.readWrite { implicit s =>
              socialUserInfoRepo.getByNetworkAndSocialIds(networkType, idGroup).values.foreach { socialUser =>
                val minutesToSubtract = Random.nextInt(minutesFromNow)
                val updatedUser = socialUser.withLastGraphRefresh(Some(now.minusMinutes(minutesToSubtract)))
                socialUserInfoRepo.save(updatedUser)
              }
            }
          }
      }
      JsString(s"updated ${socialIdsByNetwork.values.map(_.size).sum} records")
    }
  }
}
