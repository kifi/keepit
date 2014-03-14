package com.keepit.abook

import com.keepit.common.controller.ABookServiceController
import com.keepit.commanders.WTICommander
import com.keepit.common.db.Id
import com.keepit.model.{SocialUserInfo, User}

import play.api.libs.json.Json
import play.api.mvc.Action

import com.google.inject.Inject

class ABookWTIController @Inject() (wtiCommander: WTICommander) extends ABookServiceController {

  def ripestFruit(userId: Long, howMany: Int) = Action { request =>
    implicit val idFormatter = Id.format[SocialUserInfo]
    Ok(Json.toJson(wtiCommander.ripestFruit(Id[User](userId),howMany)))
  }
}
