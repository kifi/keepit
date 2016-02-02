package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.EitherFormat
import com.keepit.controllers.core.AuthHelper
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model.ExternalLibrarySpace.{ ExternalOrganizationSpace, ExternalUserSpace }
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.shoebox.controllers.{ LibraryAccessActions, OrganizationAccessActions }
import com.keepit.slack._
import com.keepit.slack.models._
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }

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
  authCommander: SlackAuthenticationCommander,
  authHelper: AuthHelper,
  pathCommander: PathCommander,
  slackToLibRepo: SlackChannelToLibraryRepo,
  libToSlackRepo: LibraryToSlackChannelRepo,
  userRepo: UserRepo,
  slackMembershipRepo: SlackTeamMembershipRepo,
  slackInfoCommander: SlackInfoCommander,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  userValueRepo: UserValueRepo,
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

  def connectSlackTeam(organizationId: PublicId[Organization], slackTeamId: Option[SlackTeamId]) = OrganizationUserAction(organizationId, SlackCommander.slackSetupPermission).async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val res = authCommander.processActionOrElseAuthenticate(request.request.userId, slackTeamId, ConnectSlackTeam(request.orgId))
    handleAsAPIRequest(res)(request.request)
  }

  def syncPublicChannels(organizationId: PublicId[Organization]) = OrganizationUserAction(organizationId, SlackCommander.slackSetupPermission).async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val slackTeamIdOpt = db.readOnlyReplica { implicit session =>
      slackInfoCommander.getOrganizationSlackInfo(request.orgId, request.request.userId).slackTeam.map(_.id)
    }
    val res = authCommander.processActionOrElseAuthenticate(request.request.userId, slackTeamIdOpt, SyncPublicChannels(request.orgId))
    handleAsAPIRequest(res)(request.request)
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
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val res = authCommander.processActionOrElseAuthenticate(request.userId, None, SetupLibraryIntegrations(Library.decodePublicId(libraryId).get))
    handleAsAPIRequest(res)
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

