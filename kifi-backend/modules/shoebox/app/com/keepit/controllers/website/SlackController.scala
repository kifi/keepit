package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.{ PermissionCommander, LibraryAccessCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.json.EitherFormat
import com.keepit.heimdal.{ HeimdalContextBuilderFactory, HeimdalContext }
import com.keepit.model.ExternalLibrarySpace.{ ExternalOrganizationSpace, ExternalUserSpace }
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import com.keepit.slack.SlackAuthenticatedAction.SetupSlackTeam
import com.keepit.slack.models._
import com.keepit.slack._
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }

@Singleton
class SlackController @Inject() (
    slackClient: SlackClient,
    slackCommander: SlackCommander,
    slackIntegrationCommander: SlackIntegrationCommander,
    slackTeamCommander: SlackTeamCommander,
    libraryAccessCommander: LibraryAccessCommander,
    deepLinkRouter: DeepLinkRouter,
    slackToLibRepo: SlackChannelToLibraryRepo,
    userRepo: UserRepo,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val permissionCommander: PermissionCommander,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  private def redirectToLibrary(libraryId: Id[Library], showSlackDialog: Boolean): Result = {
    val libraryUrl = deepLinkRouter.generateRedirect(DeepLinkRouter.libraryLink(Library.publicId(libraryId))).get.url
    val redirectUrl = if (showSlackDialog) libraryUrl + "?showSlackDialog" else libraryUrl
    Redirect(redirectUrl, SEE_OTHER)
  }

  private def redirectToOrg(orgId: Id[Organization]): Result = {
    val orgUrl = deepLinkRouter.generateRedirect(DeepLinkRouter.organizationLink(Organization.publicId(orgId))).get.url
    val redirectUrl = orgUrl
    Redirect(redirectUrl, SEE_OTHER)
  }

  def registerSlackAuthorization(codeOpt: Option[String], state: String) = UserAction.async { request =>
    implicit val scopesFormat = SlackAuthScope.dbFormat
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val resultFut = for {
      code <- codeOpt.map(Future.successful).getOrElse(Future.failed(SlackAPIFailure.NoAuthCode))
      slackAuth <- slackClient.processAuthorizationResponse(SlackAuthorizationCode(code))
      slackIdentity <- slackClient.identifyUser(slackAuth.accessToken)
      result <- {
        slackCommander.registerAuthorization(request.userId, slackAuth, slackIdentity)
        for {
          stateValue <- SlackState.toJson(SlackState(state)).toOption
          (action, dataJson) <- stateValue.asOpt(SlackAuthenticatedAction.readsWithDataJson)
          result <- dataJson.asOpt(action.readsDataAndThen(processAuthorizedAction(request.userId, slackAuth, slackIdentity, _, _)))
        } yield result
      } getOrElse Future.successful(BadRequest("invalid_state"))
    } yield result

    resultFut.recover {
      case fail: SlackAPIFailure => Redirect("/", SEE_OTHER) // we could have an explicit error page here
    }
  }

  private def processAuthorizedAction[T](userId: Id[User], slackAuth: SlackAuthorizationResponse, slackIdentity: SlackIdentifyResponse, action: SlackAuthenticatedAction[T], data: T)(implicit context: HeimdalContext): Future[Result] = {
    import SlackAuthenticatedAction._
    action match {
      case SetupLibraryIntegrations => (Library.decodePublicId(data), slackAuth.incomingWebhook) match {
        case (Success(libId), Some(webhook)) =>
          slackIntegrationCommander.setupIntegrations(userId, libId, webhook, slackIdentity)
          Future.successful(redirectToLibrary(libId, showSlackDialog = true))
        case _ => Future.successful(BadRequest("invalid_library_id"))
      }
      case TurnOnLibraryPush => (LibraryToSlackChannel.decodePublicId(data), slackAuth.incomingWebhook) match {
        case (Success(integrationId), Some(webhook)) =>
          val libraryId = slackIntegrationCommander.turnOnLibraryPush(integrationId, webhook, slackIdentity)
          Future.successful(redirectToLibrary(libraryId, showSlackDialog = true))
        case _ => Future.successful(BadRequest("invalid_integration_id"))
      }
      case TurnOnChannelIngestion => SlackChannelToLibrary.decodePublicId(data) match {
        case Success(integrationId) =>
          val libraryId = slackIntegrationCommander.turnOnChannelIngestion(integrationId, slackIdentity)
          Future.successful(redirectToLibrary(libraryId, showSlackDialog = true))
        case _ => Future.successful(BadRequest("invalid_integration_id"))
      }
      case SetupSlackTeam => ((data: Option[PublicId[Organization]]).map(Organization.decodePublicId(_).map(Some(_))) getOrElse Success(None)) match {
        case Success(orgIdOpt) =>
          slackTeamCommander.setupSlackTeam(userId, slackIdentity, orgIdOpt).map { slackTeam =>
            slackTeam.organizationId match {
              case Some(orgId) => redirectToOrg(orgId)
              case None => Redirect("/integrations/slack/teams", SEE_OTHER)
            }
          }
        case _ => Future.successful(BadRequest("invalid_organization_id"))
      }
    }
  }

  def modifyIntegrations(id: PublicId[Library]) = UserAction(parse.tolerantJson) { implicit request =>
    (request.body \ "integrations").validate[Seq[ExternalSlackIntegrationModification]] match {
      case JsError(errs) => BadRequest(Json.obj("error" -> "could_not_parse", "hint" -> errs.toString))
      case JsSuccess(mods, _) =>
        val extUserIds = mods.flatMap(_.space).collect { case ExternalUserSpace(uid) => uid }.toSet
        val pubOrgIds = mods.flatMap(_.space).collect { case ExternalOrganizationSpace(oid) => oid }.toSet
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

        val libsUserNeedsToWriteTo = db.readOnlyMaster { implicit s =>
          slackToLibMods.flatMap {
            case (stlId, mod) =>
              val stl = slackToLibRepo.get(stlId)
              if (mod.status.contains(SlackIntegrationStatus.On) && stl.status != SlackIntegrationStatus.On) Some(stl.libraryId)
              else None
          }.toSet
        }
        if (libraryAccessCommander.ensureUserCanWriteTo(request.userId, libsUserNeedsToWriteTo)) {
          slackIntegrationCommander.modifyIntegrations(SlackIntegrationModifyRequest(request.userId, libToSlackMods, slackToLibMods)).map { response =>
            Ok(Json.toJson(response))
          }.recover {
            case fail: LibraryFail => fail.asErrorResponse
          }.get
        } else {
          LibraryFail(FORBIDDEN, "cannot_write_to_library").asErrorResponse
        }
    }
  }
  def deleteIntegrations(id: PublicId[Library]) = UserAction(parse.tolerantJson) { implicit request =>
    implicit val eitherIdFormat = EitherFormat(LibraryToSlackChannel.formatPublicId, SlackChannelToLibrary.formatPublicId)
    (request.body \ "integrations").validate[Seq[Either[PublicId[LibraryToSlackChannel], PublicId[SlackChannelToLibrary]]]] match {
      case JsError(errs) => BadRequest(Json.obj("error" -> "could_not_parse", "hint" -> errs.toString))
      case JsSuccess(dels, _) =>
        val libToSlackDels = dels.collect { case Left(ltsId) => LibraryToSlackChannel.decodePublicId(ltsId).get }.toSet
        val slackToLibDels = dels.collect { case Right(stlId) => SlackChannelToLibrary.decodePublicId(stlId).get }.toSet
        slackIntegrationCommander.deleteIntegrations(SlackIntegrationDeleteRequest(request.userId, libToSlackDels, slackToLibDels)).map { response =>
          Ok(Json.toJson(response))
        }.recover {
          case fail: LibraryFail => fail.asErrorResponse
        }.get
    }
  }

  def createOrganizationForSlackTeam(slackTeamId: String) = UserAction.async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    slackTeamCommander.createOrganizationForSlackTeam(request.userId, SlackTeamId(slackTeamId)).flatMap { slackTeam =>
      slackTeamCommander.setupLatestSlackChannels(request.userId, slackTeam.slackTeamId).map { _ =>
        redirectToOrg(slackTeam.organizationId.get)
      }
    }
  }

  def connectSlackTeamToOrganization(newOrganizationId: PublicId[Organization], slackTeamId: String) = OrganizationUserAction(newOrganizationId, SlackCommander.slackSetupPermission).async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    slackTeamCommander.connectSlackTeamToOrganization(request.request.userId, SlackTeamId(slackTeamId), request.orgId) match {
      case Success(slackTeam) if slackTeam.organizationId.contains(request.orgId) =>
        slackTeamCommander.setupLatestSlackChannels(request.request.userId, slackTeam.slackTeamId).map { _ =>
          redirectToOrg(slackTeam.organizationId.get)
        }
      case Success(slackTeam) => Future.failed(new Exception(s"Something weird happen while connecting org ${request.orgId} with $slackTeam"))
      case Failure(_) => Future.successful(BadRequest("invalid_request"))
    }
  }

  def connectSlackTeam(slackTeamId: Option[String]) = UserAction { implicit request =>
    val link = SlackAPI.OAuthAuthorize(SlackAuthScope.teamSetup, SetupSlackTeam -> None, slackTeamId.map(SlackTeamId(_))).url
    Redirect(link, SEE_OTHER)
  }
}
