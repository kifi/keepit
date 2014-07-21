package com.keepit.controllers.website

import com.google.inject.Inject

import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }

import com.keepit.commanders.{ InviteCommander, ShoeboxRichConnectionCommander, FullSocialId }

class WTIController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    shoeboxRichConnectionCommander: ShoeboxRichConnectionCommander,
    inviteCommander: InviteCommander) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def block() = JsonAction.authenticated(parse.json) { request =>
    (request.body \ "fullSocialId").asOpt[FullSocialId] match {
      case None => BadRequest("0")
      case Some(fullSocialId) => {
        shoeboxRichConnectionCommander.block(request.userId, fullSocialId)
        Ok
      }
    }
  }

  def getRipestInvitees(page: Int, pageSize: Int) = JsonAction.authenticatedAsync { request =>
    inviteCommander.getRipestInvitees(request.userId, page, pageSize).map { ripestInvitees =>
      Ok(Json.toJson(ripestInvitees))
    }
  }
}

