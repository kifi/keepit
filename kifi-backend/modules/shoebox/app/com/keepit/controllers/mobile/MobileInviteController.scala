package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, MobileController, ActionAuthenticator}
import com.keepit.commanders.{InviteStatus, FullSocialId, InviteCommander}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import com.keepit.social.SocialNetworks
import scala.concurrent.Future

class MobileInviteController @Inject()(
  actionAuthenticator:ActionAuthenticator,
  inviteCommander:InviteCommander
) extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  def inviteConnection = JsonAction.authenticatedParseJsonAsync { implicit request =>
    (request.body \ "fullSocialId").asOpt[String].flatMap(FullSocialId.fromString) match {
      case None => Future.successful(BadRequest(Json.obj("code" -> "invalid_arguments")))
      case Some(fullSocialId) => {
        val subject = (request.body \ "subject").asOpt[String]
        val message = (request.body \ "message").asOpt[String]
        val source = "mobile"
        inviteCommander.invite(request.userId, fullSocialId, subject, message, source).map {
          case inviteStatus if inviteStatus.sent => Ok(Json.obj("code" -> "invitation_sent"))
          case InviteStatus(false, Some(facebookInvite), code @ "client_handle") if fullSocialId.network == SocialNetworks.FACEBOOK =>
            Ok(Json.obj("code" -> code, "link" -> inviteCommander.acceptUrl(facebookInvite.externalId), "redirectUri" -> inviteCommander.fbConfirmUrl(facebookInvite.externalId, source)))
          case _ => Status(INTERNAL_SERVER_ERROR)(Json.obj("code" -> "internal_error"))
        }
      }
    }
  }
}
