package com.keepit.controllers.core

import com.keepit.common.controller.WebsiteController
import com.keepit.common.logging.Logging
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.http.ContentTypes
import play.api.mvc._
import play.api._
import com.keepit.model._
import com.keepit.common.db.slick._
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.DBSession.RSession

@Singleton
class UserHelper @Inject() (
  userRepo: UserRepo,
  suiRepo: SocialUserInfoRepo) extends Logging {

  /* Usage: /widthxheight/userExternalId
   * /200/9de9a8c4-74aa-43fb-bdd3-f329b4a1c0f6 for a 200x200 square
   */

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
