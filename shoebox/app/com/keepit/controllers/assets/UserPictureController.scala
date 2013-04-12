package com.keepit.controllers.assets

import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import com.keepit.common.controller.{WebsiteController, ActionAuthenticator}
import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.model.SocialUserInfoRepo
import com.keepit.model.SocialUserInfoRepoImpl
import com.keepit.model.User
import com.keepit.model.UserRepo
import com.keepit.model.UserRepoImpl

import play.api.mvc.Action

@Singleton
class UserPictureController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  userRepo: UserRepo,
  suiRepo: SocialUserInfoRepo)
  extends WebsiteController(actionAuthenticator) {

  def get(width: Int, userExternalId: ExternalId[User]) = Action { request =>
    val url = db.readOnly(implicit s => getAvatarByUserExternalId(width, userExternalId))
    Redirect(url)
  }

  private val defaultAvatar = "https://graph.facebook.com//picture?type=square"

  def getAvatarByUserId(width: Int, userId: Id[User])(implicit session: RSession): String = {
    try {
      val socialUserInfo = suiRepo.getByUser(userId)
      val socialId = socialUserInfo.headOption.map(_.socialId.id).getOrElse("")
      s"https://graph.facebook.com/$socialId/picture?type=square&width=$width&height=$width"
    } catch {
      case ex: Throwable => // default avatar
        log.warn(s"Can't find a social user for $userId")
        defaultAvatar
    }
  }
  def getAvatarByUserExternalId(width: Int, userExternalId: ExternalId[User])(implicit session: RSession) = {
    try {
      val user = userRepo.get(userExternalId)
      getAvatarByUserId(width, user.id.get)
    } catch {
      case ex: Throwable => // default avatar
        log.warn(s"Can't identify user $userExternalId")
        defaultAvatar
    }
  }
}
