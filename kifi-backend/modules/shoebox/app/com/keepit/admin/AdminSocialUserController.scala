package com.keepit.controllers.admin

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._

import views.html
import com.keepit.social.{SocialGraphPlugin, SocialUserRawInfoStore}

class AdminSocialUserController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo,
  socialConnectionRepo: SocialConnectionRepo,
  socialUserRawInfoStore: SocialUserRawInfoStore,
  socialGraphPlugin: SocialGraphPlugin)
    extends AdminController(actionAuthenticator) {

  def resetSocialUser(socialUserId: Id[SocialUserInfo]) = AdminHtmlAction.authenticated { implicit request =>
    val socialUserInfo = db.readWrite { implicit s =>
      socialUserInfoRepo.save(socialUserInfoRepo.get(socialUserId).reset())
    }
    Redirect(com.keepit.controllers.admin.routes.AdminSocialUserController.socialUserView(socialUserInfo.id.get))
  }

  def socialUserView(socialUserId: Id[SocialUserInfo]) = AdminHtmlAction.authenticated { implicit request =>
    Async {
      for {
        socialUserInfo <- db.readOnlyAsync { implicit s => socialUserInfoRepo.get(socialUserId) }
        socialConnections <- db.readOnlyAsync { implicit s => socialConnectionRepo.getSocialUserConnections(socialUserId).sortWith((a,b) => a.fullName < b.fullName) }
      } yield {
        val rawInfo = socialUserRawInfoStore.get(socialUserInfo.id.get)
        Ok(html.admin.socialUser(socialUserInfo, socialConnections, rawInfo))
      }
    }
  }

  def socialUsersView(page: Int) = AdminHtmlAction.authenticated { implicit request =>
    val PAGE_SIZE = 50
    Async {
      db.readOnlyAsync { implicit s => socialUserInfoRepo.page(page, PAGE_SIZE) } map { socialUsers =>
        Ok(html.admin.socialUsers(socialUsers, page))
      }
    }
  }

  def disconnectSocialUser(suiId: Id[SocialUserInfo], revoke: Boolean = false) = AdminHtmlAction.authenticated { implicit request =>
    val sui = db.readOnly(socialUserInfoRepo.get(suiId)(_))
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

  def refreshSocialInfo(socialUserInfoId: Id[SocialUserInfo]) = AdminHtmlAction.authenticated { implicit request =>
    val socialUserInfo = db.readOnly { implicit s => socialUserInfoRepo.get(socialUserInfoId) }
    if (socialUserInfo.credentials.isEmpty) throw new Exception("can't fetch user info for user with missing credentials: %s".format(socialUserInfo))
    socialGraphPlugin.asyncFetch(socialUserInfo)
    Redirect(com.keepit.controllers.admin.routes.AdminSocialUserController.socialUserView(socialUserInfoId))
  }
}
