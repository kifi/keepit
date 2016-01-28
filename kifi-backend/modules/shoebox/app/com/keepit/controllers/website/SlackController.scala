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
import play.api.mvc.{ Request, RequestHeader, Result }
import com.keepit.common.core._

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
    pathCommander: PathCommander,
    slackToLibRepo: SlackChannelToLibraryRepo,
    libToSlackRepo: LibraryToSlackChannelRepo,
    userRepo: UserRepo,
    slackMembershipRepo: SlackTeamMembershipRepo,
    slackInfoCommander: SlackInfoCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val permissionCommander: PermissionCommander,
    val userActionsHelper: UserActionsHelper,
    val libraryAccessCommander: LibraryAccessCommander,
    val db: Database,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val ec: ExecutionContext) extends UserActions with OrganizationAccessActions with LibraryAccessActions with ShoeboxServiceController {

  private def redirectToLibrary(libraryId: Id[Library], showSlackDialog: Boolean): SlackResponse.RedirectClient = {
    val libraryUrl = db.readOnlyMaster { implicit s => pathCommander.libraryPageById(libraryId) }.absolute
    val redirectUrl = if (showSlackDialog) libraryUrl + "?showSlackDialog" else libraryUrl
    SlackResponse.RedirectClient(redirectUrl)
  }

  private def getOrgUrl(organizationId: Id[Organization]): String = {
    db.readOnlyMaster { implicit s => pathCommander.orgPageById(organizationId) }.absolute
  }

  private def redirectToOrganization(organizationId: Id[Organization], showSlackDialog: Boolean): SlackResponse.ActionPerformed = {
    val organizationUrl = getOrgUrl(organizationId)
    val redirectUrl = if (showSlackDialog) organizationUrl + "?showSlackDialog" else organizationUrl
    SlackResponse.ActionPerformed(redirectUrl)
  }

  private def redirectToOrganizationIntegrations(organizationId: Id[Organization]): SlackResponse.ActionPerformed = {
    val redirectUrl = getOrgUrl(organizationId) + "/settings/integrations"
    SlackResponse.ActionPerformed(redirectUrl)
  }

  def registerSlackAuthorization(codeOpt: Option[String], state: String) = UserAction.async { implicit request =>
    implicit val scopesFormat = SlackAuthScope.dbFormat
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val resultFut = for {
      code <- codeOpt.map(Future.successful).getOrElse(Future.failed(SlackAPIFailure.NoAuthCode))
      action <- slackStateCommander.getSlackAction(SlackAuthState(state)).map(Future.successful).getOrElse(Future.failed(SlackAPIFailure.InvalidAuthState))
      slackAuth <- slackClient.processAuthorizationResponse(SlackAuthorizationCode(code), SlackController.REDIRECT_URI)
      slackIdentity <- slackClient.identifyUser(slackAuth.accessToken)
      result <- {
        slackCommander.registerAuthorization(request.userIdOpt, slackAuth, slackIdentity)
        processAuthorizedAction(request.userId, slackIdentity.teamId, slackIdentity.userId, action, slackAuth.incomingWebhook)
      }
    } yield {
      result match {
        case SlackResponse.RedirectClient(url) => Redirect(url, SEE_OTHER) // explicit error page?
        case _ => Redirect("/", SEE_OTHER) // explicit error page?
      }
    }

    resultFut.recover {
      case fail: SlackAPIFailure => Redirect("/", SEE_OTHER) // we could have an explicit error page here
    }
  }

  private def processAuthorizedAction(userId: Id[User], slackTeamId: SlackTeamId, slackUserId: SlackUserId, action: SlackAuthenticatedAction, incomingWebhook: Option[SlackIncomingWebhook])(implicit context: HeimdalContext): Future[SlackResponse] = {
    action match {
      case SetupLibraryIntegrations(libId) => incomingWebhook match {
        case Some(webhook) =>
          slackIntegrationCommander.setupIntegrations(userId, libId, webhook, slackTeamId, slackUserId)
          Future.successful(redirectToLibrary(libId, showSlackDialog = true))
        case _ => Future.successful(SlackResponse.Error("missing_webhook"))
      }

      case TurnOnLibraryPush(integrationId) => incomingWebhook match {
        case Some(webhook) =>
          val libraryId = slackIntegrationCommander.turnOnLibraryPush(Id(integrationId), webhook, slackTeamId, slackUserId)
          Future.successful(redirectToLibrary(libraryId, showSlackDialog = true))
        case _ => Future.successful(SlackResponse.Error("missing_webhook"))
      }

      case TurnOnChannelIngestion(integrationId) => {
        val libraryId = slackIntegrationCommander.turnOnChannelIngestion(Id(integrationId), slackTeamId, slackUserId)
        Future.successful(redirectToLibrary(libraryId, showSlackDialog = true))
      }

      case AddSlackTeam() => slackTeamCommander.addSlackTeam(userId, slackTeamId).map {
        case (slackTeam, isNewOrg) =>
          slackTeam.organizationId match {
            case None => SlackResponse.RedirectClient(s"/integrations/slack/teams?slackTeamId=${slackTeam.slackTeamId.value}")
            case Some(orgId) =>
              if (isNewOrg) redirectToOrganization(orgId, showSlackDialog = true)
              else if (slackTeam.publicChannelsLastSyncedAt.isDefined) redirectToOrganization(orgId, showSlackDialog = false)
              else redirectToOrganizationIntegrations(orgId)
          }
      }

      case ConnectSlackTeam(orgId) => slackTeamCommander.connectSlackTeamToOrganization(userId, slackTeamId, orgId) match {
        case Success(team) if team.organizationId.contains(orgId) => Future.successful(redirectToOrganizationIntegrations(orgId))
        case teamMaybe =>
          val error = teamMaybe.map(team => new Exception(s"Something weird happen while connecting org $orgId with $team")).recover { case error => error }
          throw error.get
      }

      case CreateSlackTeam() => slackTeamCommander.createOrganizationForSlackTeam(userId, slackTeamId).map { team =>
        team.organizationId match {
          case Some(orgId) => redirectToOrganization(orgId, showSlackDialog = true)
          case None => throw new Exception(s"Something weird happen while creating org for $team")
        }
      }

      case SyncPublicChannels() =>
        slackTeamCommander.syncPublicChannels(userId, slackTeamId).map {
          case (orgId, _) => redirectToOrganizationIntegrations(orgId)
        }

      case _ => throw new IllegalStateException(s"Action not handled by SlackController: $action")
    }
  }

  private def processActionOrElseAuthenticate(userId: Id[User], slackTeamIdOpt: Option[SlackTeamId], action: SlackAuthenticatedAction)(implicit request: RequestHeader): Future[SlackResponse] = {
    slackCommander.getIdentityAndMissingScopes(userId, slackTeamIdOpt)(action.helper).flatMap {
      case (Some((slackTeamId, slackUserId)), missingScopes) if missingScopes.isEmpty =>
        implicit val context = heimdalContextBuilder.withRequestInfo(request).build
        processAuthorizedAction(userId, slackTeamId, slackUserId, action, None)
      case (_, missingScopes) =>
        val authUrl = slackStateCommander.getAuthLink(action, slackTeamIdOpt, missingScopes, SlackController.REDIRECT_URI).url
        Future.successful(SlackResponse.RedirectClient(authUrl))
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

  def addSlackTeam(slackTeamId: Option[SlackTeamId]) = UserAction.async { implicit request =>
    processActionOrElseAuthenticate(request.userId, slackTeamId, AddSlackTeam())
      .map(handleAsBrowserRequest)
  }

  def connectSlackTeam(organizationId: PublicId[Organization], slackTeamId: Option[SlackTeamId]) = OrganizationUserAction(organizationId, SlackCommander.slackSetupPermission).async { implicit request =>
    processActionOrElseAuthenticate(request.request.userId, slackTeamId, ConnectSlackTeam(request.orgId))
      .map(handleAsAPIRequest)
  }

  def createSlackTeam(slackTeamId: Option[SlackTeamId]) = UserAction.async { implicit request =>
    processActionOrElseAuthenticate(request.userId, slackTeamId, CreateSlackTeam())
      .map(handleAsBrowserRequest)
  }

  def syncPublicChannels(organizationId: PublicId[Organization]) = OrganizationUserAction(organizationId, SlackCommander.slackSetupPermission).async { implicit request =>
    val slackTeamIdOpt = db.readOnlyReplica { implicit session =>
      slackInfoCommander.getOrganizationSlackInfo(request.orgId, request.request.userId).slackTeam.map(_.id)
    }
    processActionOrElseAuthenticate(request.request.userId, slackTeamIdOpt, SyncPublicChannels())
      .map(handleAsAPIRequest)
  }

  def getOrganizationsToConnectToSlackTeam() = UserAction { implicit request =>
    val orgs = db.readOnlyMaster { implicit session =>
      slackTeamCommander.getOrganizationsToConnectToSlackTeam(request.userId).toSeq.sortBy(_.name)
    }
    Ok(Json.obj("orgs" -> orgs))
  }

  def getOrgIntegrations(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, SlackCommander.slackSetupPermission) { implicit request =>
    val result = db.readOnlyReplica { implicit s =>
      slackInfoCommander.getOrganizationSlackInfo(request.orgId, request.request.userId)
    }
    Ok(Json.toJson(result))
  }

  def setupLibraryIntegrations(libraryId: PublicId[Library]) = (UserAction andThen LibraryViewUserAction(libraryId)).async { implicit request =>
    processActionOrElseAuthenticate(request.userId, None, SetupLibraryIntegrations(Library.decodePublicId(libraryId).get))
      .map(handleAsAPIRequest)
  }

  def turnOnLibraryPush(libraryId: PublicId[Library], integrationId: String) = (UserAction andThen LibraryViewUserAction(libraryId)).async { implicit request =>
    LibraryToSlackChannel.decodePublicIdStr(integrationId) match {
      case Success(libToSlackId) =>
        // todo(Léo): deal with broken webhook / missing write permissions ???
        val slackTeamId = db.readOnlyMaster { implicit session => libToSlackRepo.get(libToSlackId).slackTeamId }
        processActionOrElseAuthenticate(request.userId, Some(slackTeamId), TurnOnLibraryPush(libToSlackId.id))
          .map(handleAsBrowserRequest)
      case Failure(_) => Future.successful(BadRequest("invalid_integration_id")) // Bad bad bad
    }
  }

  def turnOnChannelIngestion(libraryId: PublicId[Library], integrationId: String) = (UserAction andThen LibraryWriteAction(libraryId)).async { implicit request =>
    SlackChannelToLibrary.decodePublicIdStr(integrationId) match {
      case Success(slackToLibId) =>
        val slackTeamId = db.readOnlyMaster { implicit session => slackToLibRepo.get(slackToLibId).slackTeamId }
        processActionOrElseAuthenticate(request.userId, Some(slackTeamId), TurnOnChannelIngestion(slackToLibId.id))
          .map(handleAsBrowserRequest)
      case Failure(_) => Future.successful(BadRequest("invalid_integration_id")) // Bad bad bad
    }
  }

  // Can elegantly handle redirects (to HTML pages), *never* speaks JSON, should not be on /site/ routes
  private def handleAsBrowserRequest[T](implicit request: Request[T]) = { (response: SlackResponse) =>
    response match {
      case SlackResponse.RedirectClient(url) => Redirect(url, SEE_OTHER)
      case SlackResponse.ActionPerformed(urlOpt) =>
        val url = urlOpt.orElse(request.headers.get(REFERER).filter(_.startsWith("https://www.kifi.com"))).getOrElse("/")
        Redirect(url)
      case SlackResponse.Error(code) =>
        log.warn(s"[SlackController#handleAsBrowserRequest] Error: $code")
        Redirect("/") // Bad bad bad
    }
  }

  // Always speaks JSON, should be on /site/ routes, cannot handle Redirects to HTML pages
  private def handleAsAPIRequest[T](implicit request: Request[T]) = { (response: SlackResponse) =>
    response match {
      case SlackResponse.RedirectClient(url) => Ok(Json.obj("redirect" -> url))
      case SlackResponse.ActionPerformed(urlOpt) => Ok(Json.obj("success" -> true, "url" -> urlOpt))
      case SlackResponse.Error(code) => BadRequest(Json.obj("error" -> code))
    }
  }
}

private sealed trait SlackResponse
private object SlackResponse {
  // Error, usually can't be elegantly recovered by clients.
  final case class Error(code: String) extends SlackResponse
  // Client should blindly go to `url`, typically because they need to go to 3rd party to auth
  final case class RedirectClient(url: String) extends SlackResponse
  // Requested action performed. Url included is a suggestion about a good place to go next, but clients can be smarter.
  final case class ActionPerformed(url: Option[String] = None) extends SlackResponse
}
