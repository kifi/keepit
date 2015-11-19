package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.PermissionCommander
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.json.EitherFormat
import com.keepit.model.ExternalLibrarySpace.{ ExternalOrganizationSpace, ExternalUserSpace }
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
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
    userRepo: UserRepo,
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
    (request.body \ "integrations").validate[Seq[ExternalSlackIntegrationModification]] match {
      case JsError(errs) => BadRequest(Json.obj("error" -> "could_not_parse", "hint" -> errs.toString))
      case JsSuccess(mods, _) =>
        val extUserIds = mods.flatMap(_.space).collect { case ExternalUserSpace(uid) => uid }
        val pubOrgIds = mods.flatMap(_.space).collect { case ExternalOrganizationSpace(oid) => oid }
        val extToIntUserId = db.readOnlyReplica { implicit s =>
          userRepo.getAllUsersByExternalId(extUserIds)
        }.map { case (extId, u) => extId -> u.id.get }
        val externalToInternalSpace: Map[ExternalLibrarySpace, LibrarySpace] = {
          extToIntUserId.map { case (extId, intId) => ExternalUserSpace(extId) -> UserSpace(intId) } ++
            pubOrgIds.map { pubId => ExternalOrganizationSpace(pubId) -> OrganizationSpace(Organization.decodePublicId(pubId).get) }.toMap
        }

        val libToSlackMods = mods.collect {
          case ExternalSlackIntegrationModification(Left(ltsId), extSpace, status) =>
            LibraryToSlackChannel.decodePublicId(ltsId).get -> SlackIntegrationModification(extSpace.map(x => externalToInternalSpace(x)), status)
        }.toMap
        val slackToLibMods = mods.collect {
          case ExternalSlackIntegrationModification(Right(stlId), extSpace, status) =>
            SlackChannelToLibrary.decodePublicId(stlId).get -> SlackIntegrationModification(extSpace.map(externalToInternalSpace(_)), status)
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
