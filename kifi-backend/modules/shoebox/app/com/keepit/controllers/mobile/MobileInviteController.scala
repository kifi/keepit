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


class MobileInviteController @Inject()(
  actionAuthenticator:ActionAuthenticator,
  inviteCommander:InviteCommander,
  abookServiceClient:ABookServiceClient
) extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  private val url = current.configuration.getString("application.baseUrl").get // todo: removeme

  def inviteConnection = AuthenticatedJsonToJsonAction { implicit request =>
    Json.fromJson[InviteInfo](request.body).asOpt map { inviteInfo =>
      if (inviteInfo.fullSocialId.network == "email") {
        val userId = request.userId
        val user = request.user
        Async {
          abookServiceClient.getOrCreateEContact(userId, inviteInfo.fullSocialId.id) map { econtactTr =>
            econtactTr match {
              case Success(c) =>
                inviteCommander.sendInvitationForContact(userId, c, user, url, inviteInfo)
                log.info(s"[inviteConnection-email(${inviteInfo.fullSocialId.id}, $userId)] invite sent successfully")
                Ok(Json.obj("code" -> "invitation_sent"))
              case Failure(e) =>
                log.warn(s"[inviteConnection-email(${inviteInfo.fullSocialId.id}, $userId)] cannot locate or create econtact entry; Error: $e; Cause: ${e.getCause}")
                BadRequest(Json.obj("code" -> "invalid_arguments"))
            }
          }
        }
      } else {

        Status(NOT_IMPLEMENTED)(Json.obj("code" -> "not_implemented")) // todo
      }
    } getOrElse BadRequest(Json.obj("code" -> "invalid_arguments"))
  }

}
