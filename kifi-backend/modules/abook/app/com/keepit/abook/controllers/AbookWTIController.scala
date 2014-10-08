package com.keepit.abook.controllers

import com.keepit.common.controller.ABookServiceController
import com.keepit.common.db.Id
import com.keepit.model.{ SocialUserInfo, User }

import play.api.libs.json.{ JsNumber, Json }
import play.api.mvc.Action

import com.google.inject.Inject
import com.keepit.common.mail.EmailAddress
import com.keepit.abook.commanders.WTICommander

class ABookWTIController @Inject() (wtiCommander: WTICommander) extends ABookServiceController {

  def ripestFruit(userId: Long, howMany: Int) = Action { request =>
    implicit val idFormatter = Id.format[SocialUserInfo]
    Ok(Json.toJson(wtiCommander.ripestFruit(Id[User](userId), howMany)))
  }

  def countInvitationsSent(userId: Id[User], friendSocialId: Option[Long], friendEmailAddress: Option[EmailAddress]) = Action { request =>
    val friendId = friendSocialId.map(id => Left(Id[SocialUserInfo](id))) getOrElse Right(friendEmailAddress.get)
    Ok(JsNumber(wtiCommander.countInvitationsSent(userId, friendId)))
  }

  def getRipestFruits(userId: Id[User], page: Int, pageSize: Int) = Action { request =>
    val ripestFruits = wtiCommander.getRipestFruitsByCommonKifiFriendsCount(userId, page, pageSize)
    Ok(Json.toJson(ripestFruits))
  }
}
