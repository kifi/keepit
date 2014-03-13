package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, MobileController, ActionAuthenticator}
import com.keepit.commanders.{InviteInfo, InviteCommander}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.abook.ABookServiceClient
import scala.util.{Failure, Success}
import play.api.Play._
import scala.util.Success
import scala.util.Failure
import com.keepit.common.db.Id
import com.keepit.model.{InvitationStates, Invitation, SocialUserInfo, User}
import play.api.mvc.Result
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.social.{SocialNetworkType, SocialId, SocialNetworks}
import com.keepit.inject.FortyTwoConfig


class MobileInviteController @Inject()(
  actionAuthenticator:ActionAuthenticator,
  inviteCommander:InviteCommander,
  abookServiceClient:ABookServiceClient,
  fortytwoConfig: FortyTwoConfig
) extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  def inviteConnection = JsonAction.authenticatedParseJsonAsync { implicit request =>
    val inviteInfoOpt = Json.fromJson[InviteInfo](request.body).asOpt
    log.info(s"[inviteConnection(${request.userId})] invite=$inviteInfoOpt")
    inviteInfoOpt map { inviteInfo =>
      val userId = request.userId
      val user = request.user
      if (inviteInfo.fullSocialId.network == "email") {
        abookServiceClient.getOrCreateEContact(userId, inviteInfo.fullSocialId.id) map { econtactTr =>
          econtactTr match {
            case Success(c) =>
              inviteCommander.sendInvitationForContact(userId, c, user, inviteInfo)
              log.info(s"[inviteConnection-email(${inviteInfo.fullSocialId.id}, $userId)] invite sent successfully")
              Ok(Json.obj("code" -> "invitation_sent"))
            case Failure(e) =>
              log.warn(s"[inviteConnection-email(${inviteInfo.fullSocialId.id}, $userId)] cannot locate or create econtact entry; Error: $e; Cause: ${e.getCause}")
              BadRequest(Json.obj("code" -> "invalid_arguments"))
          }
        }
      } else {
        val inviteStatus = inviteCommander.processSocialInvite(userId, inviteInfo)
        log.info(s"[inviteConnection(${request.userId})] inviteStatus=$inviteStatus")
        if (inviteStatus.sent) resolve(Ok(Json.obj("code" -> "invitation_sent")))
        else if (inviteInfo.fullSocialId.network.equalsIgnoreCase("facebook") && inviteStatus.code == "client_handle") { // special handling
          inviteStatus.savedInvite match {
            case Some(saved) =>
              resolve(Ok(Json.obj("code" -> "client_handle", "invite" -> Json.toJson[Invitation](saved))))
            case None => { // shouldn't happen
              log.error(s"[processInvite($userId,$user,$inviteInfo)] Could not send Facebook invite")
              resolve(Status(INTERNAL_SERVER_ERROR)(Json.obj("code" -> "internal_error")))
            }
          }
        } else resolve(BadRequest(Json.obj("code" -> "invite_not_sent")))
      }
    } getOrElse resolve(BadRequest(Json.obj("code" -> "invalid_arguments")))
  }

}
