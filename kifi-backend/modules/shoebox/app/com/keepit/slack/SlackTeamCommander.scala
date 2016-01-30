package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
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

case class SlackTeamNotConnectedException(slackTeamId: SlackTeamId, slackTeamName: SlackTeamName)
  extends Exception(s"SlackTeam ${slackTeamName.value} (${slackTeamId.value}) is not connected to any organization.")

case class SlackTeamAlreadyConnectedException(slackTeamId: SlackTeamId, slackTeamName: SlackTeamName, connectedOrgId: Id[Organization])
  extends Exception(s"SlackTeam ${slackTeamName.value} (${slackTeamId.value}) is already connected to organization $connectedOrgId")

case class SlackTeamNotFoundException(slackTeamId: SlackTeamId) extends Exception(s"We could not find SlackTeam ${slackTeamId.value}")

case class InvalidSlackMembershipException(userId: Id[User], slackTeamId: SlackTeamId, slackTeamName: SlackTeamName, membership: Option[SlackTeamMembership])
  extends Exception(s"User $userId is not a valid member of SlackTeam ${slackTeamName.value} (${slackTeamId.value}): $membership")

@ImplementedBy(classOf[SlackTeamCommanderImpl])
trait SlackTeamCommander {
  def getSlackTeams(userId: Id[User]): Set[SlackTeam]
  def setupSlackTeam(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam]
  def addSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(SlackTeam, Boolean)]
  def createOrganizationForSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam]
  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Id[Organization])(implicit context: HeimdalContext): Try[SlackTeam]
  def getOrganizationsToConnectToSlackTeam(userId: Id[User])(implicit session: RSession): Set[BasicOrganization]
  def syncPublicChannels(userId: Id[User], team: SlackTeam)(implicit context: HeimdalContext): Future[Map[SlackChannelIdAndName, Either[LibraryFail, Library]]]
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

  def addSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(SlackTeam, Boolean)] = {
    val (teamOpt, connectableOrgs) = db.readOnlyMaster { implicit session =>
      (slackTeamRepo.getBySlackTeamId(slackTeamId), getOrganizationsToConnectToSlackTeam(userId))
    }
    teamOpt match {
      case Some(team) if team.organizationId.isEmpty && connectableOrgs.isEmpty => createOrganizationForSlackTeam(userId, slackTeamId).imap((_, true))
      case Some(team) => Future.successful((team, false))
      case None => Future.failed(SlackTeamNotFoundException(slackTeamId))
    }
  }

  def createOrganizationForSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam] = {
    val (slackTeamOpt, membershipOpt) = db.readOnlyMaster { implicit session =>
      val slackTeamOpt = slackTeamRepo.getBySlackTeamId(slackTeamId)
      val membershipOpt = slackTeamMembershipRepo.getByUserId(userId).find(_.slackTeamId == slackTeamId)
      (slackTeamOpt, membershipOpt)
    }

    slackTeamOpt match {
      case Some(team) => team.organizationId match {
        case None => membershipOpt.flatMap(_.getTokenIncludingScopes(SlackAuthScope.teamSetup)) match {
          case Some(validToken) =>
            slackClient.getTeamInfo(validToken).flatMap { teamInfo =>
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
                  futureAvatar.flatMap { _ =>
                    Future.fromTry {
                      connectedTeamMaybe
                    }
                  }

                case Left(error) => Future.failed(error)
              }
            }
          case None => Future.failed(InvalidSlackMembershipException(userId, team.slackTeamId, team.slackTeamName, membershipOpt))
        }
        case Some(orgId) => Future.failed(SlackTeamAlreadyConnectedException(team.slackTeamId, team.slackTeamName, orgId))
      }
      case None => Future.failed(SlackTeamNotFoundException(slackTeamId))
    }
  }

  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, newOrganizationId: Id[Organization])(implicit context: HeimdalContext): Try[SlackTeam] = {
    db.readWrite { implicit session =>
      if (permissionCommander.getOrganizationPermissions(newOrganizationId, Some(userId)).contains(SlackCommander.slackSetupPermission)) {
        slackTeamRepo.getBySlackTeamId(slackTeamId) match {
          case Some(team) => team.organizationId match {
            case None => if (slackTeamMembershipRepo.getByUserId(userId).exists(_.slackTeamId == slackTeamId)) Success {
              slackTeamRepo.save(team.copy(organizationId = Some(newOrganizationId), lastChannelCreatedAt = None))
            }
            else {
              Failure(InvalidSlackMembershipException(userId, team.slackTeamId, team.slackTeamName, None))
            }
            case Some(connectedOrgId) =>
              if (connectedOrgId == newOrganizationId) Success(team)
              else Failure(SlackTeamAlreadyConnectedException(team.slackTeamId, team.slackTeamName, connectedOrgId))
          }
          case None => Failure(SlackTeamNotFoundException(slackTeamId))
        }
      } else Failure(OrganizationFail.INSUFFICIENT_PERMISSIONS)
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

  def syncPublicChannels(userId: Id[User], team: SlackTeam)(implicit context: HeimdalContext): Future[Map[SlackChannelIdAndName, Either[LibraryFail, Library]]] = {
    team.organizationId match {
      case Some(orgId) =>
        val (membershipOpt, alreadyProcessedChannels, hasOrgPermissions) = db.readOnlyMaster { implicit session =>
          val membershipOpt = slackTeamMembershipRepo.getByUserId(userId).find(_.slackTeamId == team.slackTeamId)
          val integratedChannelIds = channelToLibRepo.getIntegrationsByOrg(orgId).filter(_.slackTeamId == team.slackTeamId).flatMap(_.slackChannelId).toSet
          val hasOrgPermissions = permissionCommander.getOrganizationPermissions(orgId, Some(userId)).contains(SlackCommander.slackSetupPermission)
          (membershipOpt, integratedChannelIds ++ team.channelsSynced, hasOrgPermissions)
        }
        if (hasOrgPermissions) {
          membershipOpt.flatMap(membership => membership.getTokenIncludingScopes(SlackAuthScope.syncPublicChannels).map((membership, _))) match {
            case Some((membership, validToken)) =>
              val onboardingAgent = slackOnboarder.getTeamAgent(team, membership)
              onboardingAgent.intro().flatMap { _ =>
                slackClient.getChannels(validToken, excludeArchived = true).flatMap { channels =>
                  def shouldBeIgnored(channel: SlackChannelInfo) = channel.isArchived || alreadyProcessedChannels.contains(channel.channelId) || team.lastChannelCreatedAt.exists(channel.createdAt <= _)
                  val channelsToIntegrate = channels.filter(!shouldBeIgnored(_)).sortBy(_.createdAt)
                  val libCreationsByChannel = channelsToIntegrate.map { ch =>
                    SlackChannelIdAndName(ch.channelId, ch.channelName) -> setupSlackChannel(team, membership, ch)
                  }.toMap
                  val newLibraries = libCreationsByChannel.collect { case (ch, Right(lib)) => ch -> lib }
                  val failedChannels = libCreationsByChannel.collect { case (ch, Left(fail)) => ch -> fail }
                  onboardingAgent.channels(membership, channels).flatMap { _ =>
                    SafeFuture(inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
                      "Created", newLibraries.size, "libraries from", team.slackTeamName.value, "channels",
                      team.organizationId.map(orgId => DescriptionElements("for", db.readOnlyMaster { implicit s => organizationInfoCommander.getBasicOrganizationHelper(orgId) }))
                    ))))
                    val updatedTeam = team
                      .withPublicChannelsSyncedAt(clock.now)
                      .withSyncedChannels(alreadyProcessedChannels ++ newLibraries.keySet.map(_.id))
                      .copy(lastChannelCreatedAt = channels.map(_.createdAt).maxOpt orElse team.lastChannelCreatedAt)
                      .copy(generalChannelId = channels.find(_.isGeneral).map(_.channelId) orElse team.generalChannelId)
                    if (updatedTeam != team) {
                      db.readWrite { implicit s => slackTeamRepo.save(updatedTeam) }
                    }
                    if (failedChannels.nonEmpty) slackLog.warn(
                      "Failed to create some libraries while integrating Slack team", team.slackTeamId.value, ".",
                      "The errors are:", failedChannels.values.map(_.getMessage).mkString("[", ",", "]")
                    )

                    Future.successful(libCreationsByChannel)
                  }
                }
              }
            case None => Future.failed(InvalidSlackMembershipException(userId, team.slackTeamId, team.slackTeamName, membershipOpt))
          }
        } else Future.failed(OrganizationFail.INSUFFICIENT_PERMISSIONS)

      case None => Future.failed(SlackTeamNotConnectedException(team.slackTeamId, team.slackTeamName))
    }
  }

  def getOrganizationsToConnectToSlackTeam(userId: Id[User])(implicit session: RSession): Set[BasicOrganization] = {
    val allOrgIds = orgMembershipRepo.getAllByUserId(userId).map(_.organizationId).toSet
    val existingSlackTeams = slackTeamRepo.getByOrganizationIds(allOrgIds)
    val validOrgIds = allOrgIds.filter(existingSlackTeams(_).isEmpty)
    organizationInfoCommander.getBasicOrganizations(validOrgIds).values.toSet
  }
}
