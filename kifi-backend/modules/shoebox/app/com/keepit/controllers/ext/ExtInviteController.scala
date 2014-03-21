package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.commanders.{InviteStatus, FullSocialId, InviteCommander}
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.social.SocialNetworks
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import scala.concurrent.Future

class ExtInviteController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  inviteCommander: InviteCommander
) extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def invite() = JsonAction.authenticatedParseJsonAsync { request =>
    val fullSocialIdOption = (request.body \ "id").asOpt[String].flatMap(FullSocialId.fromString) orElse {
      (request.body \ "email").asOpt[String].map(email => FullSocialId(SocialNetworks.EMAIL, Right(email)))
    }

    fullSocialIdOption match {
      case None => Future.successful(BadRequest("0"))
      case Some(fullSocialId) => {
        val source = (request.body \ "source").as[String]
        inviteCommander.invite(request.userId, fullSocialId, None, None, source).map {
          case inviteStatus if inviteStatus.sent => Ok(Json.obj("sent" -> true))
          case InviteStatus(false, Some(facebookInvite), "client_handle") if fullSocialId.network == SocialNetworks.FACEBOOK =>
            Ok(Json.obj("url" -> inviteCommander.fbInviteUrl(facebookInvite.externalId, fullSocialId.identifier.left.get, source)))
          case _ => InternalServerError("0")
        }
      }
    }
  }
}
