package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.util.{ DescriptionElements, LinkElement }
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.models._
import play.api.http.Status._
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[SlackIntegrationCommanderImpl])
trait SlackIntegrationCommander {
  // Open their own DB sessions, intended to be called directly from controllers
  def setupIntegrations(userId: Id[User], libId: Id[Library], webhookId: Id[SlackIncomingWebhookInfo]): Try[SlackTeam]
  def turnLibraryPush(integrationId: Id[LibraryToSlackChannel], slackTeamId: SlackTeamId, slackUserId: SlackUserId, turnOn: Boolean): Id[Library]
  def turnChannelIngestion(integrationId: Id[SlackChannelToLibrary], slackTeamId: SlackTeamId, slackUserId: SlackUserId, turnOn: Boolean): Id[Library]
  def modifyIntegrations(request: SlackIntegrationModifyRequest): Try[SlackIntegrationModifyResponse]
  def deleteIntegrations(request: SlackIntegrationDeleteRequest): Try[SlackIntegrationDeleteResponse]
  def ingestFromChannelPlease(teamId: SlackTeamId, channelId: SlackChannelId): Unit
  def getBySlackChannels(teamId: SlackTeamId, channelIds: Set[SlackChannelId])(implicit session: RSession): Map[SlackChannelId, SlackChannelIntegrations]
}

@Singleton
class SlackIntegrationCommanderImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  channelRepo: SlackChannelRepo,
  slackClient: SlackClientWrapper,
  slackOnboarder: SlackOnboarder,
  basicUserRepo: BasicUserRepo,
  pathCommander: PathCommander,
  permissionCommander: PermissionCommander,
  libRepo: LibraryRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  organizationInfoCommander: OrganizationInfoCommander,
  airbrake: AirbrakeNotifier,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  inhouseSlackClient: InhouseSlackClient)
    extends SlackIntegrationCommander with Logging {

  def setupIntegrations(userId: Id[User], libId: Id[Library], webhookId: Id[SlackIncomingWebhookInfo]): Try[SlackTeam] = {
    val teamWithPushIntegrationMaybe = db.readWrite { implicit s =>
      val webhookInfo = slackIncomingWebhookInfoRepo.get(webhookId)
      val slackTeamId = webhookInfo.slackTeamId
      val slackUserId = webhookInfo.slackUserId

      slackTeamRepo.getBySlackTeamId(slackTeamId) match {
        case Some(team) =>
          team.organizationId match {
            case Some(orgId) =>
              val webhook = webhookInfo.webhook
              channelRepo.getOrCreate(slackTeamId, webhook.channelId, webhook.channelName)

              val space = LibrarySpace.fromOrganizationId(orgId)
              val ltsc = libToChannelRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
                requesterId = userId,
                space = space,
                libraryId = libId,
                slackUserId = slackUserId,
                slackTeamId = slackTeamId,
                slackChannelId = webhook.channelId,
                slackChannelName = webhook.channelName,
                status = SlackIntegrationStatus.On
              ))
              val sctl = channelToLibRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
                requesterId = userId,
                space = space,
                libraryId = libId,
                slackUserId = slackUserId,
                slackTeamId = slackTeamId,
                slackChannelId = webhook.channelId,
                slackChannelName = webhook.channelName,
                status = SlackIntegrationStatus.Off
              ))
              Success((team, ltsc))
            case None => Failure(SlackActionFail.TeamNotConnected(team.slackTeamId, team.slackTeamName))
          }
        case None => Failure(SlackActionFail.TeamNotFound(slackTeamId))
      }
    }

    teamWithPushIntegrationMaybe map {
      case (team, pushIntegration) =>
        SafeFuture.wrap {
          val inhouseMsg = db.readOnlyReplica { implicit s =>
            import DescriptionElements._
            val lib = libRepo.get(libId)
            val user = basicUserRepo.load(userId)
            val orgOpt = lib.organizationId.flatMap(organizationInfoCommander.getBasicOrganizationHelper)
            DescriptionElements(
              user, s"set up Slack integrations between channel ${pushIntegration.slackChannelName.value} and",
              lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute),
              orgOpt.map(org => DescriptionElements("in", org.name --> LinkElement(pathCommander.orgPage(org))))
            )
          }
          inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(inhouseMsg))
        }

        slackOnboarder.talkAboutIntegration(pushIntegration)

        team
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

  def turnLibraryPush(integrationId: Id[LibraryToSlackChannel], slackTeamId: SlackTeamId, slackUserId: SlackUserId, turnOn: Boolean): Id[Library] = {
    db.readWrite { implicit session =>
      val integration = libToChannelRepo.get(integrationId)
      if (integration.isActive && integration.slackTeamId == slackTeamId) {
        val newStatus = if (turnOn) SlackIntegrationStatus.On else SlackIntegrationStatus.Off
        val updatedIntegration = {
          if (integration.slackUserId == slackUserId) integration else integration.copy(slackUserId = slackUserId)
        }.withStatus(newStatus)
        libToChannelRepo.save(updatedIntegration)
      }
      integration.libraryId
    }
  }

  def turnChannelIngestion(integrationId: Id[SlackChannelToLibrary], slackTeamId: SlackTeamId, slackUserId: SlackUserId, turnOn: Boolean): Id[Library] = {
    db.readWrite { implicit session =>
      val integration = channelToLibRepo.get(integrationId)
      if (integration.isActive && integration.slackTeamId == slackTeamId) {
        val newStatus = if (turnOn) SlackIntegrationStatus.On else SlackIntegrationStatus.Off
        val updatedIntegration = {
          if (integration.slackUserId == slackUserId) integration else integration.copy(slackUserId = slackUserId)
        }.withStatus(newStatus)
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

  def ingestFromChannelPlease(teamId: SlackTeamId, channelId: SlackChannelId): Unit = db.readWrite { implicit session =>
    channelToLibRepo.ingestFromChannelWithin(teamId, channelId, SlackIngestionConfig.maxIngestionDelayAfterCommand)
  }

  def getBySlackChannels(teamId: SlackTeamId, channelIds: Set[SlackChannelId])(implicit session: RSession): Map[SlackChannelId, SlackChannelIntegrations] = {
    val channelToLibIntegrationsByChannelId = channelToLibRepo.getBySlackTeamAndChannels(teamId, channelIds)
    val libToChannelIntegrationsByChannelId = libToChannelRepo.getBySlackTeamAndChannels(teamId, channelIds)
    channelIds.map { channelId =>
      val channelToLibIntegrations = channelToLibIntegrationsByChannelId.getOrElse(channelId, Set.empty)
      val libToChannelIntegrations = libToChannelIntegrationsByChannelId.getOrElse(channelId, Set.empty)
      val integrationSpaces = (channelToLibIntegrations.map(stl => stl.libraryId -> stl.space) ++ libToChannelIntegrations.map(lts => lts.libraryId -> lts.space)).groupBy(_._1).mapValues(_.map(_._2))
      channelId -> SlackChannelIntegrations(
        teamId = teamId,
        channelId = channelId,
        allLibraries = channelToLibIntegrations.map(_.libraryId).toSet ++ libToChannelIntegrations.map(_.libraryId),
        toLibraries = channelToLibIntegrations.collect { case integration if integration.status == SlackIntegrationStatus.On => integration.libraryId }.toSet,
        fromLibraries = libToChannelIntegrations.collect { case integration if integration.status == SlackIntegrationStatus.On => integration.libraryId }.toSet,
        spaces = integrationSpaces
      )
    }.toMap
  }
}
