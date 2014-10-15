package com.keepit.controllers.api

import play.api.libs.json.Json
import com.google.inject.Inject
import com.keepit.common.controller._
import com.keepit.common.logging.Logging

class DeskController @Inject() (
    val userActionsHelper: UserActionsHelper) extends UserActions with ShoeboxServiceController with Logging {

  def isLoggedIn = MaybeUserAction { implicit req =>
    val js = req match {
      case u: UserRequest[_] =>
        Json.obj(
          "loggedIn" -> true,
          "firstName" -> u.user.firstName,
          "lastName" -> u.user.lastName
        )
      case _ =>
        Json.obj("loggedIn" -> false)
    }
    Ok(js)
  }
}
