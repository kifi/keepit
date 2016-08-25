package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageConfig
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
  liteLibrarySlackInfoCache: LiteLibrarySlackInfoCache,
  basicOrganizationGen: BasicOrganizationGen,
  airbrake: AirbrakeNotifier,
  implicit val imageConfig: S3ImageConfig,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  inhouseSlackClient: InhouseSlackClient)
    extends SlackIntegrationCommander with Logging {

  def setupIntegrations(userId: Id[User], libId: Id[Library], webhookId: Id[SlackIncomingWebhookInfo]): Try[SlackTeam] = {
    val teamWithPushIntegrationMaybe = db.readWrite { implicit s =>
      val webhook = slackIncomingWebhookInfoRepo.get(webhookId)
      val channel = channelRepo.getByChannelId(webhook.slackTeamId, webhook.slackChannelId).get
      val slackTeamId = webhook.slackTeamId
      val slackUserId = webhook.slackUserId

      slackTeamRepo.getBySlackTeamId(slackTeamId) match {
        case Some(team) =>
          team.organizationId match {
            case Some(orgId) =>
              val ltsc = libToChannelRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
                requesterId = userId,
                libraryId = libId,
                slackUserId = slackUserId,
                slackTeamId = slackTeamId,
                slackChannelId = webhook.slackChannelId,
                status = SlackIntegrationStatus.On
              ))
              val sctl = channelToLibRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
                requesterId = userId,
                libraryId = libId,
                slackUserId = slackUserId,
                slackTeamId = slackTeamId,
                slackChannelId = webhook.slackChannelId,
                status = SlackIntegrationStatus.Off
              ))
              Success((team, channel, ltsc))
            case None => Failure(SlackActionFail.TeamNotConnected(team.slackTeamId, team.slackTeamName))
          }
        case None => Failure(SlackActionFail.TeamNotFound(slackTeamId))
      }
    }

    teamWithPushIntegrationMaybe map {
      case (team, channel, pushIntegration) =>
        SafeFuture.wrap {
          val inhouseMsg = db.readOnlyReplica { implicit s =>
            import DescriptionElements._
            val lib = libRepo.get(libId)
            val user = basicUserRepo.load(userId)
            val orgOpt = lib.organizationId.flatMap(basicOrganizationGen.getBasicOrganizationHelper)
            DescriptionElements(
              user, s"set up Slack integrations between channel ${channel.slackChannelName.value} and",
              lib.name --> LinkElement(pathCommander.pathForLibrary(lib).absolute),
              orgOpt.map(org => DescriptionElements("in", org.name --> LinkElement(pathCommander.orgPage(org))))
            )
          }
          inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(inhouseMsg))
        }

        slackOnboarder.talkAboutIntegration(pushIntegration, channel)

        team
    }
  }

  private def validateRequest(request: SlackIntegrationRequest)(implicit session: RSession): Option[LibraryFail] = {
    def hasAccessToOrg(userId: Id[User], slackTeamId: SlackTeamId) = slackTeamRepo.getBySlackTeamId(slackTeamId).exists { team =>
      team.organizationId.exists { orgId =>
        orgMembershipRepo.getByOrgIdAndUserId(orgId, userId).isDefined
      }
    }
    request match {
      case r: SlackIntegrationCreateRequest =>
        if (!hasAccessToOrg(r.requesterId, r.slackTeamId)) Some(LibraryFail(FORBIDDEN, "insufficient_permissions_for_target_space"))
        else None

      case r: SlackIntegrationDeleteRequest =>

        val slackTeamIds = libToChannelRepo.getActiveByIds(r.libToSlack).map(_.slackTeamId) ++ channelToLibRepo.getActiveByIds(r.slackToLib).map(_.slackTeamId)
        if (!slackTeamIds.forall(hasAccessToOrg(r.requesterId, _))) Some(LibraryFail(FORBIDDEN, "permission_denied"))
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
        if (newStatus != integration.status) liteLibrarySlackInfoCache.remove(LiteLibrarySlackInfoKey(integration.libraryId))
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
    return ; // ewwww
    channelToLibRepo.ingestFromChannelWithin(teamId, channelId, SlackIngestionConfig.maxIngestionDelayAfterCommand)
  }

  def getBySlackChannels(teamId: SlackTeamId, channelIds: Set[SlackChannelId])(implicit session: RSession): Map[SlackChannelId, SlackChannelIntegrations] = {
    val channelToLibIntegrationsByChannelId = channelToLibRepo.getBySlackTeamAndChannels(teamId, channelIds)
    val libToChannelIntegrationsByChannelId = libToChannelRepo.getBySlackTeamAndChannels(teamId, channelIds)
    channelIds.map { channelId =>
      val channelToLibIntegrations = channelToLibIntegrationsByChannelId.getOrElse(channelId, Set.empty)
      val libToChannelIntegrations = libToChannelIntegrationsByChannelId.getOrElse(channelId, Set.empty)
      channelId -> SlackChannelIntegrations(
        teamId = teamId,
        channelId = channelId,
        allLibraries = channelToLibIntegrations.map(_.libraryId).toSet ++ libToChannelIntegrations.map(_.libraryId),
        toLibraries = channelToLibIntegrations.collect { case integration if integration.status == SlackIntegrationStatus.On => integration.libraryId }.toSet,
        fromLibraries = libToChannelIntegrations.collect { case integration if integration.status == SlackIntegrationStatus.On => integration.libraryId }.toSet
      )
    }.toMap
  }
}
