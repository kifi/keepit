package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.commanders.{FailedInvitationException, InviteStatus, FullSocialId, InviteCommander}
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.social.SocialNetworks
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import scala.concurrent.Future
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.{BasicContact}

class ExtInviteController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  inviteCommander: InviteCommander,
  airbrake: AirbrakeNotifier
) extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def invite() = JsonAction.authenticatedParseJsonAsync { request =>
    val fullSocialIdOption = (request.body \ "id").asOpt[FullSocialId] orElse {
      (request.body \ "email").asOpt[BasicContact].map(emailContact => FullSocialId(SocialNetworks.EMAIL, Right(emailContact.email), emailContact.name))
    }

    fullSocialIdOption match {
      case None => Future.successful(BadRequest("0"))
      case Some(fullSocialId) => {
        val source = (request.body \ "source").as[String]
        inviteCommander.invite(request.userId, fullSocialId, None, None, source).map {
          case inviteStatus if inviteStatus.sent => {
            log.info(s"[invite] Invite sent: $inviteStatus")
            Ok(Json.obj("sent" -> true))
          }
          case InviteStatus(false, Some(facebookInvite), "client_handle") if fullSocialId.network == SocialNetworks.FACEBOOK =>
            val json = Json.obj("url" -> inviteCommander.fbInviteUrl(facebookInvite.externalId, fullSocialId.identifier.left.get, source))
            log.info(s"[invite] Redirecting user ${request.userId} to Facebook: $json")
            Ok(json)
          case failedInviteStatus => {
            log.error(s"[invite] Unexpected error while processing invitation from ${request.userId} to ${fullSocialId}: $failedInviteStatus")
            airbrake.notify(new FailedInvitationException(failedInviteStatus, None, Some(request.userId), Some(fullSocialId)))
            InternalServerError("0")
          }
        }
      }
    }
  }
}
