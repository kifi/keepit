package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.Clock
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.models._
import play.api.http.Status._
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

object SlackCommander {
  val slackSetupPermission = OrganizationPermission.EDIT_ORGANIZATION
}

@ImplementedBy(classOf[SlackCommanderImpl])
trait SlackCommander {
  // Open their own DB sessions, intended to be called directly from controllers
  def registerAuthorization(userId: Id[User], auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse): Unit
  def setupIntegrations(userId: Id[User], libId: Id[Library], webhook: SlackIncomingWebhook, identity: SlackIdentifyResponse): Unit
  def turnOnLibraryPush(integrationId: Id[LibraryToSlackChannel], webhook: SlackIncomingWebhook, identity: SlackIdentifyResponse): Id[Library]
  def turnOnChannelIngestion(integrationId: Id[SlackChannelToLibrary], identity: SlackIdentifyResponse): Id[Library]
  def modifyIntegrations(request: SlackIntegrationModifyRequest): Try[SlackIntegrationModifyResponse]
  def deleteIntegrations(request: SlackIntegrationDeleteRequest): Try[SlackIntegrationDeleteResponse]

  def fetchMissingChannelIds(): Future[Unit]

  def setupSlackTeam(userId: Id[User], identity: SlackIdentifyResponse, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam]
  def createOrganizationForSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam]
  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Id[Organization]): Try[SlackTeam]
  def getOrganizationsToConnect(userId: Id[User]): Map[Id[Organization], OrganizationInfo]
}

@Singleton
class SlackCommanderImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  slackClient: SlackClientWrapper,
  libToSlackPusher: LibraryToSlackChannelPusher,
  ingestionCommander: SlackIngestionCommander,
  basicUserRepo: BasicUserRepo,
  pathCommander: PathCommander,
  permissionCommander: PermissionCommander,
  orgCommander: OrganizationCommander,
  orgDomainCommander: OrganizationDomainOwnershipCommander,
  libRepo: LibraryRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  orgMembershipCommander: OrganizationMembershipCommander,
  organizationInfoCommander: OrganizationInfoCommander,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  inhouseSlackClient: InhouseSlackClient)
    extends SlackCommander with Logging {

  def registerAuthorization(userId: Id[User], auth: SlackAuthorizationResponse, identity: SlackIdentifyResponse): Unit = {
    require(auth.teamId == identity.teamId && auth.teamName == identity.teamName)
    db.readWrite { implicit s =>
      slackTeamMembershipRepo.internMembership(SlackTeamMembershipInternRequest(
        userId = userId,
        slackUserId = identity.userId,
        slackUsername = identity.userName,
        slackTeamId = auth.teamId,
        slackTeamName = auth.teamName,
        token = auth.accessToken,
        scopes = auth.scopes
      ))
      auth.incomingWebhook.foreach { webhook =>
        slackIncomingWebhookInfoRepo.save(SlackIncomingWebhookInfo(
          slackUserId = identity.userId,
          slackTeamId = identity.teamId,
          slackChannelId = None,
          webhook = webhook,
          lastPostedAt = None
        ))
      }
      slackTeamRepo.getBySlackTeamId(auth.teamId).foreach { team =>
        team.organizationId.foreach { orgId =>
          if (orgMembershipRepo.getByOrgIdAndUserId(orgId, userId).isEmpty) {
            orgMembershipCommander.unsafeAddMembership(OrganizationMembershipAddRequest(orgId, userId, userId))
          }
        }
      }
    }
  }

  def setupIntegrations(userId: Id[User], libId: Id[Library], webhook: SlackIncomingWebhook, identity: SlackIdentifyResponse): Unit = {
    db.readWrite { implicit s =>
      val defaultSpace = libRepo.get(libId).organizationId match {
        case Some(orgId) if orgMembershipRepo.getByOrgIdAndUserId(orgId, userId).isDefined =>
          LibrarySpace.fromOrganizationId(orgId)
        case _ => LibrarySpace.fromUserId(userId)
      }
      libToChannelRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
        requesterId = userId,
        space = defaultSpace,
        libraryId = libId,
        slackUserId = identity.userId,
        slackTeamId = identity.teamId,
        slackChannelId = None,
        slackChannelName = webhook.channelName
      ))
      channelToLibRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
        requesterId = userId,
        space = defaultSpace,
        libraryId = libId,
        slackUserId = identity.userId,
        slackTeamId = identity.teamId,
        slackChannelId = None,
        slackChannelName = webhook.channelName
      ))
    }

    SafeFuture {
      val welcomeMsg = db.readOnlyMaster { implicit s =>
        import DescriptionElements._
        val lib = libRepo.get(libId)
        DescriptionElements(
          "A new Kifi integration was just set up.",
          "Keeps from", lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute), "will be posted to this channel."
        )
      }
      slackClient.sendToSlack(webhook, SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(welcomeMsg)).quiet) andThen {
        case Success(()) =>
          libToSlackPusher.pushUpdatesToSlack(libId) andThen {
            case Success(_) =>
              fetchMissingChannelIds()
          }
      }

      val inhouseMsg = db.readOnlyReplica { implicit s =>
        import DescriptionElements._
        val lib = libRepo.get(libId)
        val user = basicUserRepo.load(userId)
        val orgOpt = lib.organizationId.flatMap(organizationInfoCommander.getBasicOrganizationHelper)
        DescriptionElements(
          user, s"set up Slack integrations between channel ${webhook.channelName.value} and",
          lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute),
          orgOpt.map(org => DescriptionElements("in", org.name --> LinkElement(pathCommander.orgPage(org))))
        )
      }
      inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(inhouseMsg))
    }
  }

  private def validateRequest(request: SlackIntegrationRequest)(implicit session: RSession): Option[LibraryFail] = {
    request match {
      case r: SlackIntegrationCreateRequest =>
        val userHasAccessToSpace = r.space match {
          case UserSpace(uid) => r.requesterId == uid
          case OrganizationSpace(orgId) => orgMembershipRepo.getByOrgIdAndUserId(orgId, r.requesterId).isDefined
        }
        if (!userHasAccessToSpace) Some(LibraryFail(FORBIDDEN, "insufficient_permissions_for_target_space"))
        else None

      case r: SlackIntegrationModifyRequest =>
        val toSlacks = libToChannelRepo.getActiveByIds(r.libToSlack.keySet)
        val fromSlacks = channelToLibRepo.getActiveByIds(r.slackToLib.keySet)
        val oldSpaces = fromSlacks.map(_.space) ++ toSlacks.map(_.space)
        val newSpaces = (r.libToSlack.values.flatMap(_.space) ++ r.slackToLib.values.flatMap(_.space)).toSet
        val spacesUserCannotAccess = (oldSpaces ++ newSpaces).filter {
          case UserSpace(uid) => r.requesterId != uid
          case OrganizationSpace(orgId) => orgMembershipRepo.getByOrgIdAndUserId(orgId, r.requesterId).isEmpty
        }
        def userCanWriteToIngestingLibraries = r.slackToLib.filter {
          case (stlId, mod) => mod.status.contains(SlackIntegrationStatus.On)
        }.forall {
          case (stlId, mod) =>
            fromSlacks.find(_.id.get == stlId).exists { stl =>
              // TODO(ryan): I don't know if it's a good idea to ignore permissions if the integration is already on...
              stl.status == SlackIntegrationStatus.On || permissionCommander.getLibraryPermissions(stl.libraryId, Some(r.requesterId)).contains(LibraryPermission.ADD_KEEPS)
            }
        }
        Stream(
          (oldSpaces intersect spacesUserCannotAccess).nonEmpty -> LibraryFail(FORBIDDEN, "permission_to_modify_denied"),
          (newSpaces intersect spacesUserCannotAccess).nonEmpty -> LibraryFail(FORBIDDEN, "illegal_destination_space"),
          !userCanWriteToIngestingLibraries -> LibraryFail(FORBIDDEN, "cannot_write_to_library")
        ).collect { case (true, fail) => fail }.headOption

      case r: SlackIntegrationDeleteRequest =>
        val spaces = libToChannelRepo.getActiveByIds(r.libToSlack).map(_.space) ++ channelToLibRepo.getActiveByIds(r.slackToLib).map(_.space)
        val spacesUserCannotModify = spaces.filter {
          case UserSpace(uid) => r.requesterId != uid
          case OrganizationSpace(orgId) => orgMembershipRepo.getByOrgIdAndUserId(orgId, r.requesterId).isEmpty
        }
        if (spacesUserCannotModify.nonEmpty) Some(LibraryFail(FORBIDDEN, "permission_denied"))
        else None
    }
  }

  def turnOnLibraryPush(integrationId: Id[LibraryToSlackChannel], webhook: SlackIncomingWebhook, identity: SlackIdentifyResponse): Id[Library] = {
    db.readWrite { implicit session =>
      val integration = libToChannelRepo.get(integrationId)
      if (integration.isActive && integration.slackTeamId == identity.teamId && integration.slackChannelName == webhook.channelName) {
        val updatedIntegration = {
          if (integration.slackUserId == identity.userId) integration else integration.copy(slackUserId = identity.userId, slackChannelId = None) // resetting channelId with userId to be safe
        }.withStatus(SlackIntegrationStatus.On)
        libToChannelRepo.save(updatedIntegration)
      }
      integration.libraryId
    }
  }

  def turnOnChannelIngestion(integrationId: Id[SlackChannelToLibrary], identity: SlackIdentifyResponse): Id[Library] = {
    db.readWrite { implicit session =>
      val integration = channelToLibRepo.get(integrationId)
      if (integration.isActive && integration.slackTeamId == identity.teamId) {
        val updatedIntegration = {
          if (integration.slackUserId == identity.userId) integration else integration.copy(slackUserId = identity.userId, slackChannelId = None) // resetting channelId with userId to be safe
        }.withStatus(SlackIntegrationStatus.On)
        channelToLibRepo.save(updatedIntegration)
      }
      integration.libraryId
    }
  }

  def modifyIntegrations(request: SlackIntegrationModifyRequest): Try[SlackIntegrationModifyResponse] = db.readWrite { implicit s =>
    validateRequest(request) match {
      case Some(fail) => Failure(fail)
      case None =>
        Success(unsafeModifyIntegrations(request))
    }
  }
  private def unsafeModifyIntegrations(request: SlackIntegrationModifyRequest)(implicit session: RWSession): SlackIntegrationModifyResponse = {
    request.libToSlack.foreach {
      case (ltsId, mods) => libToChannelRepo.save(libToChannelRepo.get(ltsId).withModifications(mods))
    }
    request.slackToLib.foreach {
      case (stlId, mods) => channelToLibRepo.save(channelToLibRepo.get(stlId).withModifications(mods))
    }
    SlackIntegrationModifyResponse(request.libToSlack.size + request.slackToLib.size)
  }

  def deleteIntegrations(request: SlackIntegrationDeleteRequest): Try[SlackIntegrationDeleteResponse] = db.readWrite { implicit s =>
    validateRequest(request) match {
      case Some(fail) => Failure(fail)
      case None =>
        Success(unsafeDeleteIntegrations(request))
    }
  }
  private def unsafeDeleteIntegrations(request: SlackIntegrationDeleteRequest)(implicit session: RWSession): SlackIntegrationDeleteResponse = {
    request.libToSlack.foreach { ltsId => libToChannelRepo.deactivate(libToChannelRepo.get(ltsId)) }
    request.slackToLib.foreach { stlId => channelToLibRepo.deactivate(channelToLibRepo.get(stlId)) }
    SlackIntegrationDeleteResponse(request.libToSlack.size + request.slackToLib.size)
  }

  def fetchMissingChannelIds(): Future[Unit] = {
    log.info("Fetching missing Slack channel ids.")
    val (channelsWithMissingIds, tokensWithScopesByUserIdAndTeamId) = db.readOnlyMaster { implicit session =>
      val channelsWithMissingIds = libToChannelRepo.getWithMissingChannelId() ++ channelToLibRepo.getWithMissingChannelId() ++ slackIncomingWebhookInfoRepo.getWithMissingChannelId()
      val uniqueUserIdAndTeamIds = channelsWithMissingIds.map { case (userId, teamId, channelName) => (userId, teamId) }
      val tokensWithScopesByUserIdAndTeamId = uniqueUserIdAndTeamIds.map {
        case (userId, teamId) => (userId, teamId) ->
          slackTeamMembershipRepo.getBySlackTeamAndUser(teamId, userId).flatMap(m => m.token.map((_, m.scopes)))
      }.toMap
      (channelsWithMissingIds, tokensWithScopesByUserIdAndTeamId)
    }
    log.info(s"Fetching ${channelsWithMissingIds.size} missing channel ids.")
    FutureHelpers.sequentialExec(channelsWithMissingIds) {
      case (userId, teamId, channelName) =>
        log.info(s"Fetching channelId for Slack channel $channelName via user $userId in team $teamId")
        tokensWithScopesByUserIdAndTeamId(userId, teamId) match {
          case Some((token, scopes)) if scopes.contains(SlackAuthScope.SearchRead) => slackClient.getChannelId(token, channelName).map {
            case Some(channelId) =>
              log.info(s"Found channelId $channelId for Slack channel $channelName via user $userId in team $teamId")
              db.readWrite { implicit session =>
                libToChannelRepo.fillInMissingChannelId(userId, teamId, channelName, channelId)
                channelToLibRepo.fillInMissingChannelId(userId, teamId, channelName, channelId)
                slackIncomingWebhookInfoRepo.fillInMissingChannelId(userId, teamId, channelName, channelId)
              }
            case None => airbrake.notify(s"ChannelId not found Slack for channel $channelName via user $userId in team $teamId.")
          } recover {
            case error =>
              airbrake.notify(s"Unexpected error while fetching channelId for Slack channel $channelName via user $userId in team $teamId", error)
              ()
          }
          case Some((invalidToken, invalidScopes)) =>
            Future.successful(())
          case None =>
            Future.successful(())
        }
    }
  }

  def setupSlackTeam(userId: Id[User], identity: SlackIdentifyResponse, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam] = {
    val (slackTeam, userHasNoOrg) = db.readWrite { implicit session =>
      (slackTeamRepo.internSlackTeam(identity), orgMembershipRepo.getAllByUserId(userId).isEmpty)
    }
    organizationId match {
      case Some(orgId) => Future.fromTry(connectSlackTeamToOrganization(userId, slackTeam.slackTeamId, orgId))
      case None if slackTeam.organizationId.isEmpty && userHasNoOrg => createOrganizationForSlackTeam(userId, slackTeam.slackTeamId)
      case _ => Future.successful(slackTeam)
    }
  }

  def createOrganizationForSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam] = {
    db.readOnlyMaster { implicit session =>
      val slackTeamOpt = slackTeamRepo.getBySlackTeamId(slackTeamId)
      val validTokenOpt = slackTeamMembershipRepo.getByUserId(userId).filter(_.slackTeamId == slackTeamId).flatMap(_.tokenWithScopes).collectFirst {
        case SlackTokenWithScopes(token, scopes) if scopes.contains(SlackAuthScope.TeamRead) => token
      }
      (slackTeamOpt, validTokenOpt)
    } match {
      case (Some(team), Some(token)) if team.organizationId.isEmpty =>
        slackClient.getTeamInfo(token).flatMap { teamInfo =>
          val orgInitialValues = OrganizationInitialValues(name = teamInfo.name.value, description = None, site = teamInfo.emailDomains.headOption.map(_.value))
          orgCommander.createOrganization(OrganizationCreateRequest(userId, orgInitialValues)) match {
            case Right(createdOrg) =>
              val orgId = createdOrg.newOrg.id.get
              teamInfo.emailDomains.foreach { domain => orgDomainCommander.addDomainOwnership(OrganizationDomainAddRequest(userId, orgId, domain.value)) }
              teamInfo.icon.maxByOpt(_._1).foreach {
                case (size, imageUrl) =>
                // todo(Léo): upload as org avatar
              }
              Future.fromTry(connectSlackTeamToOrganization(userId, slackTeamId, createdOrg.newOrg.id.get))

            case Left(error) => Future.failed(error)
          }
        }
      case (teamOpt, _) => Future.failed(UnauthorizedSlackTeamOrganizationModificationException(teamOpt, userId, None))
    }
  }

  private def canConnectSlackTeamToOrganization(team: SlackTeam, userId: Id[User], newOrganizationId: Id[Organization])(implicit session: RSession): Boolean = {
    val isSlackTeamMember = slackTeamMembershipRepo.getByUserId(userId).map(_.slackTeamId).contains(team.slackTeamId)
    lazy val hasOrgPermissions = permissionCommander.getOrganizationPermissions(newOrganizationId, Some(userId)).contains(SlackCommander.slackSetupPermission)
    isSlackTeamMember && hasOrgPermissions
  }

  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, newOrganizationId: Id[Organization]): Try[SlackTeam] = {
    db.readWrite { implicit session =>
      slackTeamRepo.getBySlackTeamId(slackTeamId) match {
        case Some(team) if canConnectSlackTeamToOrganization(team, userId, newOrganizationId) => Success(slackTeamRepo.save(team.copy(organizationId = Some(newOrganizationId))))
        case teamOpt => Failure(UnauthorizedSlackTeamOrganizationModificationException(teamOpt, userId, Some(newOrganizationId)))
      }
    }
  }

  def getOrganizationsToConnect(userId: Id[User]): Map[Id[Organization], OrganizationInfo] = {
    val validOrgIds = {
      val orgIds = orgMembershipCommander.getAllOrganizationsForUser(userId).toSet
      val permissions = db.readOnlyMaster { implicit session => permissionCommander.getOrganizationsPermissions(orgIds, Some(userId)) }
      orgIds.filter { orgId => permissions.get(orgId).exists(_.contains(SlackCommander.slackSetupPermission)) }
    }
    organizationInfoCommander.getOrganizationInfos(validOrgIds, Some(userId))
  }
}

sealed abstract class SlackAuthenticatedAction[T](val action: String)(implicit val format: Format[T]) {
  def readsDataAndThen[R](f: (SlackAuthenticatedAction[T], T) => R): Reads[R] = format.map { data => f(this, data) }
}
object SlackAuthenticatedAction {
  case object SetupLibraryIntegrations extends SlackAuthenticatedAction[PublicId[Library]]("setup_library_integrations")
  case object TurnOnLibraryPush extends SlackAuthenticatedAction[PublicId[LibraryToSlackChannel]]("turn_on_library_push")
  case object TurnOnChannelIngestion extends SlackAuthenticatedAction[PublicId[SlackChannelToLibrary]]("turn_on_channel_ingestion")
  case object SetupSlackTeam extends SlackAuthenticatedAction[Option[PublicId[Organization]]]("setup_slack_team")

  val all: Set[SlackAuthenticatedAction[_]] = Set(SetupLibraryIntegrations, TurnOnLibraryPush, TurnOnChannelIngestion, SetupSlackTeam)

  case class UnknownSlackAuthenticatedActionException(action: String) extends Exception(s"Unknown SlackAuthenticatedAction: $action")
  def fromString(action: String): Try[SlackAuthenticatedAction[_]] = {
    all.collectFirst {
      case authAction if authAction.action equalsIgnoreCase action => Success(authAction)
    } getOrElse Failure(UnknownSlackAuthenticatedActionException(action))
  }

  private implicit val format: Format[SlackAuthenticatedAction[_]] = Format(
    Reads(_.validate[String].flatMap[SlackAuthenticatedAction[_]](action => SlackAuthenticatedAction.fromString(action).map(JsSuccess(_)).recover { case error => JsError(error.getMessage) }.get)),
    Writes(action => JsString(action.action))
  )

  implicit def writesWithData[T]: Writes[(SlackAuthenticatedAction[T], T)] = Writes { case (action, data) => Json.obj("action" -> action, "data" -> action.format.writes(data)) }
  implicit def toState[T](actionWithData: (SlackAuthenticatedAction[T], T)): SlackState = SlackState(Json.toJson(actionWithData))

  def readsWithDataJson: Reads[(SlackAuthenticatedAction[_], JsValue)] = Reads { value =>
    for {
      action <- (value \ "action").validate[SlackAuthenticatedAction[_]]
      dataJson <- (value \ "data").validate[JsValue]
    } yield (action, dataJson)
  }
}

