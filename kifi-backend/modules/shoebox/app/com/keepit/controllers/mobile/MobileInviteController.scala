package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ ShoeboxServiceController, MobileController, ActionAuthenticator }
import com.keepit.commanders.{ FailedInvitationException, InviteStatus, FullSocialId, InviteCommander }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import com.keepit.social.SocialNetworks
import scala.concurrent.Future
import com.keepit.common.healthcheck.AirbrakeNotifier

class MobileInviteController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    inviteCommander: InviteCommander,
    airbrake: AirbrakeNotifier) extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  def inviteConnection = JsonAction.authenticatedParseJsonAsync { implicit request =>
    (request.body \ "fullSocialId").asOpt[FullSocialId] match {
      case None => Future.successful(BadRequest("0"))
      case Some(fullSocialId) => {
        val subject = (request.body \ "subject").asOpt[String]
        val message = (request.body \ "message").asOpt[String]
        val source = "mobile"
        inviteCommander.invite(request.userId, fullSocialId, subject, message, source).map {
          case inviteStatus if inviteStatus.sent => {
            log.info(s"[inviteConnection] Invite sent: $inviteStatus")
            Ok(Json.obj("code" -> "invitation_sent"))
          }
          case InviteStatus(false, Some(facebookInvite), code @ "client_handle") if fullSocialId.network == SocialNetworks.FACEBOOK =>
            val json = Json.obj("code" -> code, "link" -> inviteCommander.acceptUrl(facebookInvite.externalId), "redirectUri" -> inviteCommander.fbConfirmUrl(facebookInvite.externalId, source))
            log.info(s"[inviteConnection] Redirecting user ${request.userId} to Facebook: $json")
            Ok(json)
          case failedInviteStatus => {
            log.error(s"[inviteConnection] Unexpected error while processing invitation from ${request.userId} to ${fullSocialId}: $failedInviteStatus")
            airbrake.notify(new FailedInvitationException(failedInviteStatus, None, Some(request.userId), Some(fullSocialId)))
            Status(INTERNAL_SERVER_ERROR)(Json.obj("code" -> "internal_error"))
          }
        }
      }
    }
  }
}
