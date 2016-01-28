package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.core.eitherExtensionOps
import com.keepit.common.core.{ anyExtensionOps, _ }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.SlackLog
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.DescriptionElements
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.models._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[SlackTeamCommanderImpl])
trait SlackTeamCommander {
  def getSlackTeams(userId: Id[User]): Set[SlackTeam]
  def setupSlackTeam(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam]
  def addSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam]
  def createOrganizationForSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam]
  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Id[Organization])(implicit context: HeimdalContext): Try[SlackTeam]
  def getOrganizationsToConnectToSlackTeam(userId: Id[User])(implicit session: RSession): Set[BasicOrganization]
  def syncPublicChannels(userId: Id[User], teamId: SlackTeamId)(implicit context: HeimdalContext): Future[(Id[Organization], Map[SlackChannelIdAndName, Either[LibraryFail, Library]])]
}

@Singleton
class SlackTeamCommanderImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  channelRepo: SlackChannelRepo,
  slackClient: SlackClientWrapper,
  slackOnboarder: SlackOnboarder,
  pathCommander: PathCommander,
  permissionCommander: PermissionCommander,
  orgCommander: OrganizationCommander,
  orgAvatarCommander: OrganizationAvatarCommander,
  libraryRepo: LibraryRepo,
  libraryCommander: LibraryCommander,
  orgMembershipRepo: OrganizationMembershipRepo,
  orgMembershipCommander: OrganizationMembershipCommander,
  organizationInfoCommander: OrganizationInfoCommander,
  clock: Clock,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends SlackTeamCommander {
  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)

  def getSlackTeams(userId: Id[User]): Set[SlackTeam] = {
    db.readOnlyMaster { implicit session =>
      val slackTeamIds = slackTeamMembershipRepo.getByUserId(userId).map(_.slackTeamId).toSet
      slackTeamRepo.getBySlackTeamIds(slackTeamIds).values.toSet
    }
  }

  def setupSlackTeam(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam] = {
    val (slackTeam, connectableOrgs) = db.readWrite { implicit session =>
      (slackTeamRepo.getBySlackTeamId(slackTeamId).get, getOrganizationsToConnectToSlackTeam(userId))
    }
    organizationId match {
      case Some(orgId) => Future.fromTry(connectSlackTeamToOrganization(userId, slackTeamId, orgId))
      case None if slackTeam.organizationId.isEmpty && connectableOrgs.isEmpty => createOrganizationForSlackTeam(userId, slackTeamId)
      case _ => Future.successful(slackTeam)
    }
  }

  def addSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam] = {
    val (teamOpt, connectableOrgs) = db.readOnlyMaster { implicit session =>
      (slackTeamRepo.getBySlackTeamId(slackTeamId), getOrganizationsToConnectToSlackTeam(userId))
    }
    teamOpt match {
      case Some(team) if team.organizationId.isEmpty && connectableOrgs.isEmpty => createOrganizationForSlackTeam(userId, slackTeamId)
      case Some(team) => Future.successful(team)
      case _ => Future.failed(UnauthorizedSlackTeamOrganizationModificationException(teamOpt, userId, None))
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
          val orgInitialValues = OrganizationInitialValues(name = teamInfo.name.value, description = None)
          orgCommander.createOrganization(OrganizationCreateRequest(userId, orgInitialValues)) match {
            case Right(createdOrg) =>
              val orgId = createdOrg.newOrg.id.get
              val futureAvatar = teamInfo.icon.maxByOpt(_._1) match {
                case None => Future.successful(())
                case Some((_, imageUrl)) => orgAvatarCommander.persistRemoteOrganizationAvatars(orgId, imageUrl).imap(_ => ())
              }
              teamInfo.emailDomains.exists { domain =>
                orgCommander.modifyOrganization(OrganizationModifyRequest(userId, orgId, OrganizationModifications(site = Some(domain.value)))).isRight
              }
              val connectedTeamMaybe = connectSlackTeamToOrganization(userId, slackTeamId, orgId)
              futureAvatar.flatMap { _ => Future.fromTry(connectedTeamMaybe) }
            case Left(error) =>
              Future.failed(error)
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

  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, newOrganizationId: Id[Organization])(implicit context: HeimdalContext): Try[SlackTeam] = {
    db.readWrite { implicit session =>
      slackTeamRepo.getBySlackTeamId(slackTeamId) match {
        case Some(team) if canConnectSlackTeamToOrganization(team, userId, newOrganizationId) => Success {
          if (team.organizationId.contains(newOrganizationId)) team
          else slackTeamRepo.save(team.copy(organizationId = Some(newOrganizationId), lastChannelCreatedAt = None))
        }
        case teamOpt => Failure(UnauthorizedSlackTeamOrganizationModificationException(teamOpt, userId, Some(newOrganizationId)))
      }
    } map { team =>
      SafeFuture(inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
        "Connected Slack team", team.slackTeamName.value, "to Kifi org", db.readOnlyMaster { implicit s => organizationInfoCommander.getBasicOrganizationHelper(newOrganizationId) }
      ))))
      team
    }
  }

  private def setupSlackChannel(team: SlackTeam, membership: SlackTeamMembership, channel: SlackChannelInfo)(implicit context: HeimdalContext): Either[LibraryFail, Library] = {
    require(membership.slackTeamId == team.slackTeamId, s"SlackTeam ${team.id.get}/${team.slackTeamId.value} doesn't match SlackTeamMembership ${membership.id.get}/${membership.slackTeamId.value}")
    require(membership.userId.isDefined, s"SlackTeamMembership ${membership.id.get} doesn't belong to any user.")

    val userId = membership.userId.get
    val libraryName = channel.channelName.value
    val librarySpace = LibrarySpace(userId, team.organizationId)

    // If this channel is the slack team's general channel, try to sync it with the org's general library
    // if not, just create a library as normal
    def createLibrary() = {
      val maybeOrgGeneralLibrary = if (channel.isGeneral) {
        db.readWrite { implicit s =>
          libraryRepo.getBySpaceAndKind(librarySpace, LibraryKind.SYSTEM_ORG_GENERAL).headOption.map { orgGeneral =>
            libraryRepo.save(orgGeneral.withName(channel.channelName.value))
          }
        }
      } else None

      maybeOrgGeneralLibrary.map(Right(_)).getOrElse {
        val initialValues = LibraryInitialValues(
          name = libraryName,
          visibility = librarySpace match {
            case UserSpace(_) => LibraryVisibility.SECRET
            case OrganizationSpace(_) => LibraryVisibility.ORGANIZATION
          },
          kind = Some(LibraryKind.SLACK_CHANNEL),
          description = channel.purpose.map(_.value) orElse channel.topic.map(_.value),
          space = Some(librarySpace)
        )
        libraryCommander.createLibrary(initialValues, userId)
      }
    }

    createLibrary() tap {
      case Left(_) =>
      case Right(library) =>
        db.readWrite { implicit session =>
          channelRepo.getOrCreate(team.slackTeamId, channel.channelId, channel.channelName)
          libToChannelRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
            requesterId = userId,
            space = librarySpace,
            libraryId = library.id.get,
            slackUserId = membership.slackUserId,
            slackTeamId = membership.slackTeamId,
            slackChannelId = Some(channel.channelId),
            slackChannelName = channel.channelName,
            status = SlackIntegrationStatus.On
          ))
          channelToLibRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
            requesterId = userId,
            space = librarySpace,
            libraryId = library.id.get,
            slackUserId = membership.slackUserId,
            slackTeamId = membership.slackTeamId,
            slackChannelId = Some(channel.channelId),
            slackChannelName = channel.channelName,
            status = SlackIntegrationStatus.On
          ))
        }
    }
  }

  def syncPublicChannels(userId: Id[User], teamId: SlackTeamId)(implicit context: HeimdalContext): Future[(Id[Organization], Map[SlackChannelIdAndName, Either[LibraryFail, Library]])] = {
    val (teamOpt, membershipOpt, alreadyProcessedChannels) = db.readOnlyMaster { implicit session =>
      val teamOpt = slackTeamRepo.getBySlackTeamId(teamId)
      val membershipOpt = slackTeamMembershipRepo.getByUserId(userId).find(_.slackTeamId == teamId)
      val integratedChannelIds = teamOpt.flatMap(_.organizationId).map { orgId =>
        channelToLibRepo.getIntegrationsByOrg(orgId).filter(_.slackTeamId == teamId).flatMap(_.slackChannelId).toSet
      } getOrElse Set.empty
      (teamOpt, membershipOpt, integratedChannelIds ++ teamOpt.map(_.channelsSynced).getOrElse(Set.empty))
    }
    (teamOpt, membershipOpt) match {
      case (Some(team), Some(membership)) if membership.token.isDefined && team.organizationId.isDefined =>
        slackOnboarder.talkAboutTeam(team, membership)
        slackClient.getChannels(membership.token.get, excludeArchived = true).map { channels =>
          def shouldBeIgnored(channel: SlackChannelInfo) = channel.isArchived || alreadyProcessedChannels.contains(channel.channelId) || team.lastChannelCreatedAt.exists(channel.createdAt <= _)
          val libCreationsByChannel = channels.sortBy(_.createdAt).collect {
            case channel if !shouldBeIgnored(channel) =>
              SlackChannelIdAndName(channel.channelId, channel.channelName) -> setupSlackChannel(team, membership, channel)
          }.toMap
          val newLibraries = libCreationsByChannel.collect { case (ch, Right(lib)) => ch -> lib }
          val failedChannels = libCreationsByChannel.collect { case (ch, Left(fail)) => ch -> fail }

          SafeFuture(inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
            "Created", newLibraries.size, "libraries from", team.slackTeamName.value, "channels",
            team.organizationId.map(orgId => DescriptionElements("for", db.readOnlyMaster { implicit s => organizationInfoCommander.getBasicOrganizationHelper(orgId) }))
          ))))
          val updatedTeam = team
            .withPublicChannelsSyncedAt(clock.now)
            .withSyncedChannels(alreadyProcessedChannels ++ newLibraries.keySet.map(_.id)) |> { t =>
              channels.map(_.createdAt).maxOpt.map { lastChannelCreatedAt => t.copy(lastChannelCreatedAt = Some(lastChannelCreatedAt)) } getOrElse t
            } |> { t =>
              channels.find(_.isGeneral).map { generalChannel => t.withGeneralChannelId(generalChannel.channelId) } getOrElse t
            }
          if (updatedTeam != team) {
            db.readWrite { implicit s => slackTeamRepo.save(updatedTeam) }
          }
          if (failedChannels.nonEmpty) slackLog.warn(
            "Failed to create some libraries while integrating Slack team", teamId.value, ".",
            "The errors are:", failedChannels.values.map(_.getMessage).mkString("[", ",", "]")
          )

          (team.organizationId.get, libCreationsByChannel)
        }
      case _ => Future.failed(InvalidSlackSetupException(userId, teamOpt, membershipOpt))
    }
  }

  def getOrganizationsToConnectToSlackTeam(userId: Id[User])(implicit session: RSession): Set[BasicOrganization] = {
    val allOrgIds = orgMembershipRepo.getAllByUserId(userId).map(_.organizationId).toSet
    val existingSlackTeams = slackTeamRepo.getByOrganizationIds(allOrgIds)
    val validOrgIds = allOrgIds.filter(existingSlackTeams(_).isEmpty)
    organizationInfoCommander.getBasicOrganizations(validOrgIds).values.toSet
  }
}
