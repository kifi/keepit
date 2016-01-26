package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.common.json.EitherFormat
import com.keepit.controllers.core.AuthHelper
import com.keepit.heimdal.{ HeimdalContext, HeimdalContextBuilderFactory }
import com.keepit.model.ExternalLibrarySpace.{ ExternalOrganizationSpace, ExternalUserSpace }
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.shoebox.controllers.{ LibraryAccessActions, OrganizationAccessActions }
import com.keepit.slack._
import com.keepit.slack.models._
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object SlackController {
  val REDIRECT_URI = "https://www.kifi.com/oauth2/slack"
}

@Singleton
class SlackController @Inject() (
    slackClient: SlackClient,
    slackCommander: SlackCommander,
    slackStateCommander: SlackAuthStateCommander,
    slackIntegrationCommander: SlackIntegrationCommander,
    slackTeamCommander: SlackTeamCommander,
    authCommander: AuthCommander,
    authHelper: AuthHelper,
    deepLinkRouter: DeepLinkRouter,
    slackToLibRepo: SlackChannelToLibraryRepo,
    libToSlackRepo: LibraryToSlackChannelRepo,
    userRepo: UserRepo,
    slackInfoCommander: SlackInfoCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val permissionCommander: PermissionCommander,
    val userActionsHelper: UserActionsHelper,
    val libraryAccessCommander: LibraryAccessCommander,
    val db: Database,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with OrganizationAccessActions with LibraryAccessActions with ShoeboxServiceController {

  private def redirectToLibrary(libraryId: Id[Library], showSlackDialog: Boolean): Result = {
    val libraryUrl = deepLinkRouter.generateRedirect(DeepLinkRouter.libraryLink(Library.publicId(libraryId))).get.url
    val redirectUrl = if (showSlackDialog) libraryUrl + "?showSlackDialog" else libraryUrl
    Redirect(redirectUrl, SEE_OTHER)
  }

  private def getOrgUrl(orgId: Id[Organization]): String = {
    deepLinkRouter.generateRedirect(DeepLinkRouter.organizationLink(Organization.publicId(orgId))).get.url
  }

  def registerSlackAuthorization(codeOpt: Option[String], state: String) = UserAction.async { implicit request =>
    implicit val scopesFormat = SlackAuthScope.dbFormat
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val resultFut = for {
      code <- codeOpt.map(Future.successful).getOrElse(Future.failed(SlackAPIFailure.NoAuthCode))
      action <- slackStateCommander.getSlackAction(SlackAuthState(state)).map(Future.successful(_)).getOrElse(Future.failed(SlackAPIFailure.InvalidAuthState))
      slackAuth <- slackClient.processAuthorizationResponse(SlackAuthorizationCode(code), SlackController.REDIRECT_URI)
      slackIdentity <- slackClient.identifyUser(slackAuth.accessToken)
      result <- {
        slackCommander.registerAuthorization(request.userIdOpt, slackAuth, slackIdentity)
        processAuthorizedAction(request.userId, slackAuth, slackIdentity, action)
      }
    } yield result

    resultFut.recover {
      case fail: SlackAPIFailure => Redirect("/", SEE_OTHER) // we could have an explicit error page here
    }
  }

  private def processAuthorizedAction[T](userId: Id[User], slackAuth: SlackAuthorizationResponse, slackIdentity: SlackIdentifyResponse, action: SlackAuthenticatedAction)(implicit context: HeimdalContext): Future[Result] = {
    action match {
      case SetupLibraryIntegrations(libId) => slackAuth.incomingWebhook match {
        case Some(webhook) =>
          slackIntegrationCommander.setupIntegrations(userId, libId, webhook, slackIdentity)
          Future.successful(redirectToLibrary(libId, showSlackDialog = true))
        case _ => Future.successful(BadRequest("missing_webhook"))
      }

      case TurnOnLibraryPush(integrationId) => slackAuth.incomingWebhook match {
        case Some(webhook) =>
          val libraryId = slackIntegrationCommander.turnOnLibraryPush(Id(integrationId), webhook, slackIdentity)
          Future.successful(redirectToLibrary(libraryId, showSlackDialog = true))
        case _ => Future.successful(BadRequest("missing_webhook"))
      }

      case TurnOnChannelIngestion(integrationId) => {
        val libraryId = slackIntegrationCommander.turnOnChannelIngestion(Id(integrationId), slackIdentity)
        Future.successful(redirectToLibrary(libraryId, showSlackDialog = true))
      }

      case SetupSlackTeam(orgIdOpt) => {
        slackTeamCommander.setupSlackTeam(userId, slackIdentity.teamId, orgIdOpt).map { slackTeam =>
          slackTeam.organizationId match {
            case Some(orgId) => Redirect(getOrgUrl(orgId), SEE_OTHER)
            case None => Redirect(s"/integrations/slack/teams?slackTeamId=${slackTeam.slackTeamId.value}", SEE_OTHER)
          }
        }
      }

      case AddSlackTeam() => slackTeamCommander.addSlackTeam(userId, slackIdentity.teamId).map { slackTeam =>
        slackTeam.organizationId match {
          case Some(orgId) => Redirect(getOrgUrl(orgId), SEE_OTHER)
          case None => Redirect(s"/integrations/slack/teams?slackTeamId=${slackTeam.slackTeamId.value}", SEE_OTHER)
        }
      }

      case ConnectSlackTeam(orgId) => slackTeamCommander.connectSlackTeamToOrganization(userId, slackIdentity.teamId, orgId) match {
        case Success(team) if team.organizationId.contains(orgId) => Future.successful(Redirect(getOrgUrl(orgId), SEE_OTHER))
        case teamMaybe =>
          val error = teamMaybe.map(team => new Exception(s"Something weird happen while connecting org $orgId with $team")).recover { case error => error }
          throw error.get
      }

      case CreateSlackTeam() => slackTeamCommander.createOrganizationForSlackTeam(userId, slackIdentity.teamId).map { team =>
        team.organizationId match {
          case Some(orgId) => Redirect(getOrgUrl(orgId), SEE_OTHER)
          case None => throw new Exception(s"Something weird happen while creating org for $team")
        }
      }

      case SyncPublicChannels() =>
        slackTeamCommander.syncPublicChannels(userId, slackIdentity.teamId).map {
          case (orgId, _) =>
            Redirect(getOrgUrl(orgId), SEE_OTHER)
        }

      case _ => throw new IllegalStateException(s"Action not handled by SlackController: $action")
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

  def addSlackTeam(slackTeamId: Option[String]) = UserAction { implicit request =>
    val link = slackStateCommander.getAuthLink(AddSlackTeam(), slackTeamId.map(SlackTeamId(_)), SlackController.REDIRECT_URI).url
    Redirect(link, SEE_OTHER)
  }

  def connectSlackTeam(organizationId: PublicId[Organization], slackTeamId: Option[String]) = OrganizationUserAction(organizationId, SlackCommander.slackSetupPermission) { implicit request =>
    val link = slackStateCommander.getAuthLink(ConnectSlackTeam(request.orgId), slackTeamId.map(SlackTeamId(_)), SlackController.REDIRECT_URI).url
    Redirect(link, SEE_OTHER)
  }

  def createSlackTeam(slackTeamId: Option[String]) = UserAction { implicit request =>
    val link = slackStateCommander.getAuthLink(CreateSlackTeam(), slackTeamId.map(SlackTeamId(_)), SlackController.REDIRECT_URI).url
    Redirect(link, SEE_OTHER)
  }

  def syncPublicChannels(organizationId: PublicId[Organization]) = OrganizationUserAction(organizationId, SlackCommander.slackSetupPermission) { implicit request =>
    val Seq(slackTeamId) = db.readOnlyReplica { implicit session =>
      slackInfoCommander.getOrganizationSlackInfo(request.orgId, request.request.userId).slackTeams.toSeq
    }
    val link = slackStateCommander.getAuthLink(SyncPublicChannels(), Some(slackTeamId), SlackController.REDIRECT_URI).url
    Redirect(link, SEE_OTHER)
  }

  def getOrganizationsToConnectToSlackTeam() = UserAction { implicit request =>
    val orgs = db.readOnlyMaster { implicit session =>
      slackTeamCommander.getOrganizationsToConnectToSlackTeam(request.userId).toSeq.sortBy(_.name)
    }
    Ok(Json.obj("orgs" -> orgs))
  }

  // deprecated
  def createOrganizationForSlackTeam(slackTeamId: String) = UserAction.async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    slackTeamCommander.createOrganizationForSlackTeam(request.userId, SlackTeamId(slackTeamId)).map { slackTeam =>
      slackTeam.organizationId match {
        case Some(orgId) =>
          slackTeamCommander.syncPublicChannels(request.userId, SlackTeamId(slackTeamId))
          Ok(Json.obj("redirectUrl" -> getOrgUrl(orgId)))
        case _ => throw new Exception(s"Something weird happen while creating org for $slackTeam")
      }
    }
  }

  // deprecated
  def connectOrganizationToSlackTeam(newOrganizationId: PublicId[Organization], slackTeamId: String) = OrganizationUserAction(newOrganizationId, SlackCommander.slackSetupPermission) { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    slackTeamCommander.connectSlackTeamToOrganization(request.request.userId, SlackTeamId(slackTeamId), request.orgId) match {
      case Success(slackTeam) if slackTeam.organizationId.contains(request.orgId) =>
        slackTeamCommander.syncPublicChannels(request.request.userId, SlackTeamId(slackTeamId))
        Ok(Json.obj("redirectUrl" -> getOrgUrl(request.orgId)))
      case slackTeamMaybe => throw new Exception(s"Something weird happen while connecting org ${request.orgId} with $slackTeamMaybe")
    }
  }

  def getOrgIntegrations(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, SlackCommander.slackSetupPermission) { implicit request =>
    val result = db.readOnlyReplica { implicit s =>
      slackInfoCommander.getOrganizationSlackInfo(request.orgId, request.request.userId)
    }
    Ok(Json.toJson(result))
  }

  def setupLibraryIntegrations(libraryId: PublicId[Library]) = (UserAction andThen LibraryViewAction(libraryId)) { implicit request =>
    Library.decodePublicId(libraryId) match {
      case Success(libId) =>
        val link = slackStateCommander.getAuthLink(SetupLibraryIntegrations(libId), None, SlackController.REDIRECT_URI).url
        Redirect(link, SEE_OTHER)
      case Failure(_) => BadRequest("invalid_library_id")
    }
  }

  def turnOnLibraryPush(libraryId: PublicId[Library], integrationId: String) = (UserAction andThen LibraryViewAction(libraryId)) { implicit request =>
    LibraryToSlackChannel.decodePublicIdStr(integrationId) match {
      case Success(libToSlackId) =>
        val slackTeamId = db.readOnlyMaster { implicit session => libToSlackRepo.get(libToSlackId).slackTeamId }
        val link = slackStateCommander.getAuthLink(TurnOnLibraryPush(libToSlackId.id), Some(slackTeamId), SlackController.REDIRECT_URI).url
        Redirect(link, SEE_OTHER)
      case Failure(_) => BadRequest("invalid_integration_id")
    }
  }

  def turnOnChannelIngestion(libraryId: PublicId[Library], integrationId: String) = (UserAction andThen LibraryWriteAction(libraryId)) { implicit request =>
    SlackChannelToLibrary.decodePublicIdStr(integrationId) match {
      case Success(slackToLibId) =>
        val slackTeamId = db.readOnlyMaster { implicit session => slackToLibRepo.get(slackToLibId).slackTeamId }
        val link = slackStateCommander.getAuthLink(TurnOnChannelIngestion(slackToLibId.id), Some(slackTeamId), SlackController.REDIRECT_URI).url
        Redirect(link, SEE_OTHER)
      case Failure(_) => BadRequest("invalid_integration_id")
    }
  }
}
