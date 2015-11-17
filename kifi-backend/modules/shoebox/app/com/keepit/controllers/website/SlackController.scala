package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.PermissionCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.json.EitherFormat
import com.keepit.model.{ LibraryFail, Library }
import com.keepit.slack.models._
import com.keepit.slack.{ LibraryToSlackChannelPusher, SlackClient, SlackCommander }
import play.api.libs.json.{ JsObject, JsSuccess, Json, JsError }

import scala.concurrent.ExecutionContext

@Singleton
class SlackController @Inject() (
    slackClient: SlackClient,
    slackCommander: SlackCommander,
    deepLinkRouter: DeepLinkRouter,
    libraryToSlackChannelProcessor: LibraryToSlackChannelPusher,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with ShoeboxServiceController {

  def registerSlackAuthorization(code: String, state: String) = UserAction.async { request =>
    implicit val scopesFormat = SlackAuthScope.dbFormat
    val stateObj = SlackState.toJson(SlackState(state)).toOption.flatMap(_.asOpt[JsObject])
    val libIdOpt = stateObj.flatMap(obj => (obj \ "lid").asOpt[PublicId[Library]].flatMap(lid => Library.decodePublicId(lid).toOption))

    val redir = stateObj.flatMap(deepLinkRouter.generateRedirect).map(r => r.url + "?showSlackDialog").getOrElse("/")

    val authFut = for {
      slackAuth <- slackClient.processAuthorizationResponse(SlackAuthorizationCode(code))
      slackIdentity <- slackClient.identifyUser(slackAuth.accessToken)
    } yield {
      slackCommander.registerAuthorization(request.userId, slackAuth, slackIdentity)
      (libIdOpt, slackAuth.incomingWebhook) match {
        case (Some(libId), Some(webhook)) => slackCommander.setupIntegrations(request.userId, libId, webhook, slackIdentity)
        case _ =>
      }
      Redirect(redir, SEE_OTHER)
    }

    authFut.recover {
      case fail: SlackAPIFailure => fail.asResponse
    }
  }

  // TODO(ryan): account for permissions!
  def modifyIntegrations(id: PublicId[Library]) = UserAction(parse.tolerantJson) { implicit request =>
    (request.body \ "integrations").validate[Seq[SlackIntegrationModification]] match {
      case JsError(errs) => BadRequest(Json.obj("error" -> "could_not_parse", "hint" -> errs.toString))
      case JsSuccess(mods, _) =>
        val libToSlackMods = mods.collect {
          case SlackIntegrationModification(Left(ltsId), status) => LibraryToSlackChannel.decodePublicId(ltsId).get -> status
        }.toMap
        val slackToLibMods = mods.collect {
          case SlackIntegrationModification(Right(stlId), status) => SlackChannelToLibrary.decodePublicId(stlId).get -> status
        }.toMap
        slackCommander.modifyIntegrations(SlackIntegrationModifyRequest(request.userId, libToSlackMods, slackToLibMods)).map { response =>
          Ok(Json.toJson(response))
        }.recover {
          case fail: LibraryFail => fail.asErrorResponse
        }.get
    }
  }
  def deleteIntegrations(id: PublicId[Library]) = UserAction(parse.tolerantJson) { implicit request =>
    implicit val eitherIdFormat = EitherFormat(LibraryToSlackChannel.formatPublicId, SlackChannelToLibrary.formatPublicId)
    (request.body \ "integrations").validate[Seq[Either[PublicId[LibraryToSlackChannel], PublicId[SlackChannelToLibrary]]]] match {
      case JsError(errs) => BadRequest(Json.obj("error" -> "could_not_parse", "hint" -> errs.toString))
      case JsSuccess(dels, _) =>
        val libToSlackDels = dels.collect { case Left(ltsId) => LibraryToSlackChannel.decodePublicId(ltsId).get }.toSet
        val slackToLibDels = dels.collect { case Right(stlId) => SlackChannelToLibrary.decodePublicId(stlId).get }.toSet
        slackCommander.deleteIntegrations(SlackIntegrationDeleteRequest(request.userId, libToSlackDels, slackToLibDels)).map { response =>
          Ok(Json.toJson(response))
        }.recover {
          case fail: LibraryFail => fail.asErrorResponse
        }.get
    }
  }

  // TODO(ryan): this is for testing only, remove it
  def triggerIntegrations(pubId: PublicId[Library]) = UserAction { implicit request =>
    val libId = Library.decodePublicId(pubId).get
    val userId = request.userId
    libraryToSlackChannelProcessor.pushToLibrary(libId)
    Ok
  }
}
