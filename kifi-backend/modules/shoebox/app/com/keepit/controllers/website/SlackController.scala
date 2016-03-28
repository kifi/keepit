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
    val slackTeamIdOpt = db.readOnlyMaster { implicit session =>
      slackInfoCommander.getOrganizationSlackTeam(request.orgId, request.request.userId).map(_.id)
    }
    val action = ConnectSlackTeam(request.orgId, andThen = Some(SyncPublicChannels()))
    val res = slackAuthCommander.processActionOrElseAuthenticate(request.request.userId, slackTeamIdOpt, action)
    handleAsAPIRequest(res)(request.request)
  }

  def syncPrivateChannels(organizationId: PublicId[Organization]) = OrganizationUserAction(organizationId, SlackIdentityCommander.slackSetupPermission).async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val slackTeamIdOpt = db.readOnlyMaster { implicit session =>
      slackInfoCommander.getOrganizationSlackTeam(request.orgId, request.request.userId).map(_.id)
    }
    val action = ConnectSlackTeam(request.orgId, andThen = Some(SyncPrivateChannels()))
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
    val result = db.readOnlyMaster { implicit s =>
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
        val res = slackAuthCommander.processActionOrElseAuthenticate(request.userId, Some(integration.slackTeamId), turnAction)
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
        val res = slackAuthCommander.processActionOrElseAuthenticate(request.userId, Some(integration.slackTeamId), turnAction)
        handleAsAPIRequest(res)
      case Failure(_) => Future.successful(BadRequest("invalid_integration_id"))
    }
  }

  def mirrorComments(organizationId: PublicId[Organization], turnOn: Boolean) = OrganizationUserAction(organizationId, SlackIdentityCommander.slackSetupPermission).async { implicit request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    val slackTeamIdOpt = db.readOnlyReplica { implicit session =>
      slackInfoCommander.getOrganizationSlackTeam(request.orgId, request.request.userId).map(_.id)
    }
    slackTeamIdOpt match {
      case Some(slackTeamId) =>
        val action = TurnCommentMirroring(turnOn)
        val res = slackAuthCommander.processActionOrElseAuthenticate(request.request.userId, Some(slackTeamId), action)
        handleAsAPIRequest(res)(request.request)
      case _ => Future.successful(SlackActionFail.OrgNotConnected(request.orgId).asErrorResponse)
    }
  }

  def togglePersonalDigest(slackTeamId: SlackTeamId, slackUserId: SlackUserId, turnOn: Boolean) = UserAction.async { implicit request =>
    slackTeamCommander.togglePersonalDigests(request.userId, slackTeamId, slackUserId, turnOn).recover {
      case fail: SlackFail => fail.asResponse
      case ex =>
        airbrake.notify(s"unhandled $ex when toggling personal digest for ${request.userId} ")
        InternalServerError
    }.map(_ => Ok(Json.obj("success" -> true)))
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

