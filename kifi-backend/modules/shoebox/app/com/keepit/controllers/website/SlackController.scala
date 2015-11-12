package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.PermissionCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.shoebox.controllers.OrganizationAccessActions
import com.keepit.slack.models.{ SlackAPIFailure, SlackAuthScope, SlackAuthorizationCode }
import com.keepit.slack.{ SlackClient, SlackCommander }

import scala.concurrent.ExecutionContext

@Singleton
class SlackController @Inject() (
    slackClient: SlackClient,
    slackCommander: SlackCommander,
    deepLinkRouter: DeepLinkRouter,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def registerSlackAuthorization(code: String, state: String) = UserAction.async { request =>
    implicit val scopesFormat = SlackAuthScope.dbFormat
    val redir = slackClient.decodeState(state).toOption.flatMap(deepLinkRouter.generateRedirect).map(_.url).getOrElse("/")

    val authFut = for {
      slackAuth <- slackClient.processAuthorizationResponse(SlackAuthorizationCode(code))
      slackIdentity <- slackClient.identifyUser(slackAuth.accessToken)
    } yield {
      slackCommander.registerAuthorization(request.userId, slackAuth, slackIdentity)
      Redirect(redir, OK)
    }

    authFut.recover {
      case fail: SlackAPIFailure => fail.asResponse
    }
  }
}
