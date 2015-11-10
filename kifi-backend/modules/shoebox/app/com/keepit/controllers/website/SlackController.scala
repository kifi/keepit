package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.PermissionCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.shoebox.controllers.OrganizationAccessActions
import com.keepit.slack.{ SlackAPIFail, SlackAuthorizationCode, SlackClient }
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext

@Singleton
class SlackController @Inject() (
    slackClient: SlackClient,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def registerSlackAuthorization(code: String, state: String) = UserAction.async { request =>
    slackClient.processAuthorizationResponse(SlackAuthorizationCode(code), state).map { authTry =>
      authTry.map {
        case (auth, redirState) =>
          Ok(Json.obj("auth" -> auth.scopes, "redir" -> redirState))
      }.recover {
        case fail: SlackAPIFail => fail.asResponse
      }.get
    }
  }
}
