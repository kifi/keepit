package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.PermissionCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.model.Library
import com.keepit.shoebox.controllers.OrganizationAccessActions
import com.keepit.slack.models.{ SlackIntegrationRequest, SlackAPIFailure, SlackAuthScope, SlackAuthorizationCode }
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
    val stateObj = slackClient.decodeState(state).toOption
    val libIdOpt = stateObj.flatMap(obj => (obj \ "lid").asOpt[PublicId[Library]].flatMap(lid => Library.decodePublicId(lid).toOption))

    val redir = stateObj.flatMap(deepLinkRouter.generateRedirect).map(_.url).getOrElse("/")

    val authFut = for {
      slackAuth <- slackClient.processAuthorizationResponse(SlackAuthorizationCode(code))
      slackIdentity <- slackClient.identifyUser(slackAuth.accessToken)
    } yield {
      slackCommander.registerAuthorization(request.userId, slackAuth, slackIdentity)
      (libIdOpt, slackAuth.incomingWebhook) match {
        case (Some(libId), Some(webhook)) => slackCommander.setupIntegration(request.userId, libId, webhook, slackIdentity)
        case _ =>
      }
      Redirect(redir, SEE_OTHER)
    }

    authFut.recover {
      case fail: SlackAPIFailure => fail.asResponse
    }
  }
}
