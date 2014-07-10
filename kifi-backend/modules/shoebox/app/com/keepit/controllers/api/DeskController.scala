package com.keepit.controllers.api

import play.api.libs.json.Json
import com.google.inject.Inject
import com.keepit.common.controller.{ ShoeboxServiceController, WebsiteController, ActionAuthenticator }
import com.keepit.common.logging.Logging

class DeskController @Inject() (
    actionAuthenticator: ActionAuthenticator) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController with Logging {

  def isLoggedIn = JsonAction(allowPending = false)(
    request => Ok(Json.obj(
      "loggedIn" -> true,
      "firstName" -> request.user.firstName,
      "lastName" -> request.user.lastName
    )),
    request => Ok(Json.obj("loggedIn" -> false))
  )
}
