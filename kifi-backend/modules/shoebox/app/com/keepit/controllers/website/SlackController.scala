package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.PermissionCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.shoebox.controllers.OrganizationAccessActions
import com.keepit.slack.{ SlackAuthScope, SlackAPIFail, SlackAuthorizationCode, SlackClient }
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext

@Singleton
class SlackController @Inject() (
    slackClient: SlackClient,
    deepLinkRouter: DeepLinkRouter,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def registerSlackAuthorization(code: String, state: String) = UserAction.async { request =>
    implicit val scopesFormat = SlackAuthScope.dbFormat
    slackClient.processAuthorizationResponse(SlackAuthorizationCode(code), state).map {
      case (auth, redirState) => Ok(Json.obj("auth" -> auth.scopes, "state" -> redirState, "redir" -> deepLinkRouter.generateRedirect(redirState).map(_.url)))
    }.recover {
      case fail: SlackAPIFail => fail.asResponse
    }
  }
}
