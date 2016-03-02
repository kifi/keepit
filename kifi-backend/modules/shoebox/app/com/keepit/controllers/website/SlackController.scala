package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.EitherFormat
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model.ExternalLibrarySpace.{ ExternalOrganizationSpace, ExternalUserSpace }
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.shoebox.controllers.{ LibraryAccessActions, OrganizationAccessActions }
import com.keepit.slack._
import com.keepit.slack.models._
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@Singleton
class SlackController @Inject() (
  slackIntegrationCommander: SlackIntegrationCommander,
  slackTeamCommander: SlackTeamCommander,
  slackAuthCommander: SlackAuthenticationCommander,
  slackToLibRepo: SlackChannelToLibraryRepo,
  libToSlackRepo: LibraryToSlackChannelRepo,
  userRepo: UserRepo,
  slackInfoCommander: SlackInfoCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  orgExperimentRepo: OrganizationExperimentRepo,
  val permissionCommander: PermissionCommander,
  val userActionsHelper: UserActionsHelper,
  val libraryAccessCommander: LibraryAccessCommander,
  val db: Database,
  airbrake: AirbrakeNotifier,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val ec: ExecutionContext)
    extends UserActions with OrganizationAccessActions with LibraryAccessActions with ShoeboxServiceController {

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

  def createSlackTeam(slackTeamId: Option[SlackTeamId], slackState: Option[String]) = UserAction.async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val andThen = slackState.flatMap(state => slackAuthCommander.getSlackAction(SlackAuthState(state)))
    val res = slackAuthCommander.processActionOrElseAuthenticate(request.userId, slackTeamId, CreateSlackTeam(andThen))
    handleAsAPIRequest(res)(request)
  }

  def connectSlackTeam(organizationId: PublicId[Organization], slackTeamId: Option[SlackTeamId], slackState: Option[String]) = OrganizationUserAction(organizationId, SlackIdentityCommander.slackSetupPermission).async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val andThen = slackState.flatMap(state => slackAuthCommander.getSlackAction(SlackAuthState(state)))
    val res = slackAuthCommander.processActionOrElseAuthenticate(request.request.userId, slackTeamId, ConnectSlackTeam(request.orgId, andThen))
    handleAsAPIRequest(res)(request.request)
  }

  def syncPublicChannels(organizationId: PublicId[Organization]) = OrganizationUserAction(organizationId, SlackIdentityCommander.slackSetupPermission).async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val (slackTeamIdOpt, useKifiBot) = db.readOnlyReplica { implicit session =>
      val slackTeamIdOpt = slackInfoCommander.getOrganizationSlackTeam(request.orgId, request.request.userId).map(_.id)
      val useKifiBot = orgExperimentRepo.hasExperiment(request.orgId, OrganizationExperimentType.SLACK_COMMENT_MIRRORING)
      (slackTeamIdOpt, useKifiBot)
    }
    val action = ConnectSlackTeam(request.orgId, andThen = Some(SyncPublicChannels(useKifiBot)))
    val res = slackAuthCommander.processActionOrElseAuthenticate(request.request.userId, slackTeamIdOpt, action)
    handleAsAPIRequest(res)(request.request)
  }

  def getOrganizationsToConnectToSlackTeam() = UserAction { implicit request =>
    val orgs = db.readOnlyMaster { implicit session =>
      slackTeamCommander.getOrganizationsToConnectToSlackTeam(request.userId).toSeq.sortBy(_.name)
    }
    Ok(Json.obj("orgs" -> orgs))
  }

  def getOrgIntegrations(pubId: PublicId[Organization], max: Int = 100) = OrganizationUserAction(pubId, SlackIdentityCommander.slackSetupPermission) { implicit request =>
    val result = db.readOnlyReplica { implicit s =>
      slackInfoCommander.getOrganizationSlackInfo(request.orgId, request.request.userId, Some(max))
    }
    Ok(Json.toJson(result))
  }

  def setupLibraryIntegrations(libraryId: PublicId[Library]) = (UserAction andThen LibraryViewUserAction(libraryId)).async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val action = AddSlackTeam(andThen = Some(SetupLibraryIntegrations(Library.decodePublicId(libraryId).get, None)))
    val res = slackAuthCommander.processActionOrElseAuthenticate(request.userId, None, action)
    handleAsAPIRequest(res)
  }

  def pushLibrary(libraryId: PublicId[Library], integrationIdStr: String, turnOn: Boolean) = (UserAction andThen LibraryViewUserAction(libraryId)).async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    LibraryToSlackChannel.decodePublicIdStr(integrationIdStr) match {
      case Success(integrationId) =>
        val integration = db.readOnlyMaster { implicit session => libToSlackRepo.get(integrationId) }
        val isBroken = integration.status == SlackIntegrationStatus.Broken
        val turnAction = TurnLibraryPush(integrationId.id, isBroken = isBroken, turnOn = turnOn)
        val action = if (turnOn) AddSlackTeam(andThen = Some(turnAction)) else turnAction // Wrapping with AddSlackTeam for now to encourage people to backfill / connect
        val res = slackAuthCommander.processActionOrElseAuthenticate(request.userId, Some(integration.slackTeamId), action)
        handleAsAPIRequest(res)
      case Failure(_) => Future.successful(BadRequest("invalid_integration_id"))
    }
  }

  def ingestChannel(libraryId: PublicId[Library], integrationIdStr: String, turnOn: Boolean) = (UserAction andThen LibraryWriteOrJoinAction(libraryId)).async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    SlackChannelToLibrary.decodePublicIdStr(integrationIdStr) match {
      case Success(integrationId) =>
        val integration = db.readOnlyMaster { implicit session => slackToLibRepo.get(integrationId) }
        val turnAction = TurnChannelIngestion(integrationId.id, turnOn = turnOn)
        val action = if (turnOn) AddSlackTeam(andThen = Some(turnAction)) else turnAction // Wrapping with AddSlackTeam for now to encourage people to backfill / connect
        val res = slackAuthCommander.processActionOrElseAuthenticate(request.userId, Some(integration.slackTeamId), action)
        handleAsAPIRequest(res)
      case Failure(_) => Future.successful(BadRequest("invalid_integration_id"))
    }
  }

  // Always speaks JSON, should be on /site/ routes, cannot handle Redirects to HTML pages
  private def handleAsAPIRequest[T](response: Future[SlackResponse])(implicit request: UserRequest[T]) = {
    response.map {
      case SlackResponse.RedirectClient(url) => Ok(Json.obj("redirect" -> url))
      case SlackResponse.ActionPerformed(urlOpt) =>
        Ok(Json.obj("success" -> true, "redirect" -> urlOpt))
    }.recover {
      case fail: SlackActionFail =>
        log.warn(s"[SlackController#handleAsAPIRequest] Error: ${fail.code}")
        fail.asErrorResponse
    }
  }
}

