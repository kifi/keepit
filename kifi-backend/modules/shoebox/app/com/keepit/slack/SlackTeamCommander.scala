package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.SlackLogging
import com.keepit.common.time._
import com.keepit.common.util.{ DescriptionElements, LinkElement, Ord }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.models._
import org.joda.time.Period

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[SlackTeamCommanderImpl])
trait SlackTeamCommander {
  def setupSlackTeam(userId: Id[User], identity: SlackIdentifyResponse, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam]
  def createOrganizationForSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam]
  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Id[Organization])(implicit context: HeimdalContext): Future[SlackTeam]
  def getOrganizationsToConnect(userId: Id[User]): Map[Id[Organization], OrganizationInfo]
  def setupSlackChannel(team: SlackTeam, membership: SlackTeamMembership, channel: SlackChannelInfo)(implicit context: HeimdalContext): Either[LibraryFail, Library]
  def setupLatestSlackChannels(userId: Id[User], teamId: SlackTeamId)(implicit context: HeimdalContext): Future[Map[SlackChannelIdAndName, Either[LibraryFail, Library]]]
}

@Singleton
class SlackTeamCommanderImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  slackClient: SlackClientWrapper,
  pathCommander: PathCommander,
  permissionCommander: PermissionCommander,
  orgCommander: OrganizationCommander,
  orgAvatarCommander: OrganizationAvatarCommander,
  libraryCommander: LibraryCommander,
  orgMembershipRepo: OrganizationMembershipRepo,
  orgMembershipCommander: OrganizationMembershipCommander,
  organizationInfoCommander: OrganizationInfoCommander,
  implicit val executionContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration,
  inhouseSlackClient: InhouseSlackClient)
    extends SlackTeamCommander {

  def setupSlackTeam(userId: Id[User], identity: SlackIdentifyResponse, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam] = {
    val (slackTeam, userHasNoOrg) = db.readWrite { implicit session =>
      (slackTeamRepo.internSlackTeam(identity), orgMembershipRepo.getAllByUserId(userId).isEmpty)
    }
    organizationId match {
      case Some(orgId) => connectSlackTeamToOrganization(userId, slackTeam.slackTeamId, orgId)
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
              val futureAvatar = teamInfo.icon.maxByOpt(_._1) match {
                case None => Future.successful(())
                case Some((_, imageUrl)) => orgAvatarCommander.persistRemoteOrganizationAvatars(orgId, imageUrl).imap(_ => ())
              }
              val connectedTeamMaybe = connectSlackTeamToOrganization(userId, slackTeamId, createdOrg.newOrg.id.get)
              futureAvatar.flatMap { _ => connectedTeamMaybe }
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

  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, newOrganizationId: Id[Organization])(implicit context: HeimdalContext): Future[SlackTeam] = {
    db.readWrite { implicit session =>
      slackTeamRepo.getBySlackTeamId(slackTeamId) match {
        case Some(team) if canConnectSlackTeamToOrganization(team, userId, newOrganizationId) => Success {
          if (team.organizationId.contains(newOrganizationId)) team
          else slackTeamRepo.save(team.copy(organizationId = Some(newOrganizationId), lastChannelCreatedAt = None))
        }
        case teamOpt => Failure(UnauthorizedSlackTeamOrganizationModificationException(teamOpt, userId, Some(newOrganizationId)))
      }
    } match {
      case Success(team) =>
        SafeFuture(inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
          "Connected Slack team", team.slackTeamName.value, "to Kifi org", db.readOnlyMaster { implicit s => organizationInfoCommander.getBasicOrganizationHelper(newOrganizationId) }
        ))))
        setupLatestSlackChannels(userId, team.slackTeamId).map { _ => db.readOnlyMaster { implicit session => slackTeamRepo.get(team.id.get) } }
      case Failure(error) => Future.failed(error)
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

  def setupSlackChannel(team: SlackTeam, membership: SlackTeamMembership, channel: SlackChannelInfo)(implicit context: HeimdalContext): Either[LibraryFail, Library] = {
    require(membership.slackTeamId == team.slackTeamId, s"SlackTeam ${team.id.get}/${team.slackTeamId.value} doesn't match SlackTeamMembership ${membership.id.get}/${membership.slackTeamId.value}")
    require(membership.userId.isDefined, s"SlackTeamMembership ${membership.id.get} doesn't belong to any user.")

    val userId = membership.userId.get
    val libraryName = channel.channelName.value
    val librarySpace = LibrarySpace(userId, team.organizationId)

    val initialValues = LibraryInitialValues(
      name = libraryName,
      visibility = librarySpace match {
        case UserSpace(_) => LibraryVisibility.SECRET
        case OrganizationSpace(_) => LibraryVisibility.ORGANIZATION
      },
      slug = LibrarySlug.generateFromName(libraryName),
      kind = Some(LibraryKind.SLACK_CHANNEL),
      description = channel.purpose.map(_.value) orElse channel.topic.map(_.value),
      space = Some(librarySpace)
    )
    libraryCommander.createLibrary(initialValues, userId) tap {
      case Left(_) =>
      case Right(library) =>
        db.readWrite { implicit session =>
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

  def setupLatestSlackChannels(userId: Id[User], teamId: SlackTeamId)(implicit context: HeimdalContext): Future[Map[SlackChannelIdAndName, Either[LibraryFail, Library]]] = {
    val (teamOpt, membershipOpt, integratedChannelIds) = db.readOnlyMaster { implicit session =>
      val teamOpt = slackTeamRepo.getBySlackTeamId(teamId)
      val membershipOpt = slackTeamMembershipRepo.getByUserId(userId).find(_.slackTeamId == teamId)
      val integratedChannelIds = teamOpt.flatMap(_.organizationId).map { orgId =>
        channelToLibRepo.getIntegrationsByOrg(orgId).filter(_.slackTeamId == teamId).flatMap(_.slackChannelId).toSet
      } getOrElse Set.empty
      (teamOpt, membershipOpt, integratedChannelIds)
    }
    (teamOpt, membershipOpt) match {
      case (Some(team), Some(membership)) if membership.token.isDefined && team.organizationId.isDefined =>
        slackClient.getChannels(membership.token.get, excludeArchived = true).map { channels =>
          def shouldBeIgnored(channel: SlackChannelInfo) = channel.isArchived || integratedChannelIds.contains(channel.channelId) || team.lastChannelCreatedAt.exists(channel.createdAt <= _)
          channels.sortBy(_.createdAt).collect {
            case channel if !shouldBeIgnored(channel) =>
              SlackChannelIdAndName(channel.channelId, channel.channelName) -> setupSlackChannel(team, membership, channel)
          }.toMap tap { newLibraries =>
            if (newLibraries.values.forall(_.isRight)) {
              SafeFuture(inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
                "Created", newLibraries.size, "libraries from", team.slackTeamName.value, "channels",
                team.organizationId.map(orgId => DescriptionElements("for", db.readOnlyMaster { implicit s => organizationInfoCommander.getBasicOrganizationHelper(orgId) }))
              ))))
              channels.map(_.createdAt).maxOpt.foreach { lastChannelCreatedAt =>
                db.readWrite { implicit sessio =>
                  slackTeamRepo.save(team.copy(lastChannelCreatedAt = Some(lastChannelCreatedAt)))
                }
              }
            }
          }
        }
      case _ => Future.failed(InvalidSlackSetupException(userId, teamOpt, membershipOpt))
    }
  }
}
