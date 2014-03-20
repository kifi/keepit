package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.abook.ABookServiceClient
import com.keepit.commanders.{FullSocialId, InviteCommander, InviteInfo}
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db.Id
import com.keepit.model.{EContact, SocialUserInfo, SocialUserBasicInfo, User, InvitationRepo}
import com.keepit.social.{SocialId, SocialNetworkType}

import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class ExtInviteController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  abookServiceClient: ABookServiceClient,
  inviteCommander: InviteCommander)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def invite() = JsonAction.authenticatedParseJsonAsync { request =>
    val userId = request.userId
    val source = (request.body \ "source").as[String]
    (request.body \ "id").asOpt[String] map FullSocialId.apply map { fullSocialId =>
      val status = inviteCommander.processSocialInvite(userId, InviteInfo(fullSocialId, None, None), source)
      if (!status.sent && fullSocialId.network.equalsIgnoreCase("facebook") && status.code == "client_handle") {
        status.savedInvite match {
          case Some(invitation) =>
            Future.successful(Ok(Json.obj("url" -> inviteCommander.fbInviteUrl(invitation, fullSocialId.socialId))))
          case None => { // shouldn't happen TODO: improve InviteCommander's API not to return values that "shouldn't happen"
            log.error(s"[invite($userId,$fullSocialId)] Could not send Facebook invite")
            Future.successful(InternalServerError("0"))
          }
        }
      } else {
        Future.successful(Ok(Json.obj("sent" -> status.sent)))
      }
    } orElse (request.body \ "email").asOpt[String].map { emailAddr =>
      abookServiceClient.getOrCreateEContact(userId, emailAddr) map { econtactTr =>
        econtactTr match {
          case Success(c) =>  // TODO: refactor InviteCommander not to require a FullSocialId for invitations by email (the null below)
            inviteCommander.sendInvitationForContact(userId, c, request.user, InviteInfo(null, None, None), source)
            log.info(s"[invite($userId,$emailAddr)] invite sent successfully")
            Ok(Json.obj("sent" -> true))
          case Failure(e) =>  // TODO: did abookServiceClient already Airbrake?
            log.warn(s"[invite($userId,$emailAddr)] cannot locate or create econtact entry; Error: $e; Cause: ${e.getCause}")
            InternalServerError("0")
        }
      }
    } getOrElse {
      Future.successful(BadRequest("0"))
    }
  }

}
