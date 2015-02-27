package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.TwitterWaitlistCommander
import com.keepit.common.controller.{ UserActionsHelper, UserActions, ShoeboxServiceController }
import com.keepit.common.time.Clock
import com.keepit.model._
import play.api.libs.json.Json

class TwitterWaitlistController @Inject() (
    commander: TwitterWaitlistCommander,
    val userActionsHelper: UserActionsHelper) extends UserActions with ShoeboxServiceController {

  //DO NOT USE THE WORD *FAKE* IN THE ROUTE FOR THIS!!!
  def getFakeWaitlistPoition(handle: String) = UserAction { request =>
    commander.getFakeWaitlistPosition(request.userId, handle).map { pos =>
      Ok(Json.obj(
        "pos" -> pos
      ))
    } getOrElse {
      NotFound
    }
  }

  //DO NOT USE THE WORD *FAKE* IN THE ROUTE FOR THIS!!!
  def getFakeWaitlistLength(handle: String) = UserAction { request =>
    Ok(Json.obj(
      "length" -> commander.getFakeWaitlistLength()
    ))
  }

}
