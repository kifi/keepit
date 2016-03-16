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
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.{ Clock, _ }
import com.keepit.common.util.{ LinkElement, DescriptionElements }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.SlackTeamCommander.SlackChannelLibraries
import com.keepit.slack.models._
import scala.concurrent.duration._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

object SlackTeamCommander {
  type SlackChannelLibraries = Map[SlackChannelIdAndName, Either[LibraryFail, Library]]
  val channelSyncTimeout = 20 minutes
  val channelSyncBuffer = 10 seconds
}

@ImplementedBy(classOf[SlackTeamCommanderImpl])
trait SlackTeamCommander {
  def getSlackTeams(userId: Id[User]): Set[SlackTeam]
  def getSlackTeamOpt(slackTeamId: SlackTeamId): Option[SlackTeam]
  def setupSlackTeam(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam]
  def addSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(SlackTeam, Boolean)]
  def createOrganizationForSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam]
  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Id[Organization])(implicit context: HeimdalContext): Try[SlackTeam]
  def getOrganizationsToConnectToSlackTeam(userId: Id[User])(implicit session: RSession): Set[BasicOrganization]
  def syncPublicChannels(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(Id[Organization], Set[SlackChannelIdAndName], Future[SlackChannelLibraries])]
  def turnCommentMirroring(userId: Id[User], slackTeamId: SlackTeamId, turnOn: Boolean): Try[Id[Organization]]
  def togglePersonalDigests(slackTeamId: SlackTeamId, slackUserId: SlackUserId, turnOn: Boolean): Future[Unit]
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
  permissionCommander: PermissionCommander,
  orgCommander: OrganizationCommander,
  pathCommander: PathCommander,
  orgAvatarCommander: OrganizationAvatarCommander,
  libraryRepo: LibraryRepo,
  libraryCommander: LibraryCommander,
  orgMembershipRepo: OrganizationMembershipRepo,
  organizationInfoCommander: OrganizationInfoCommander,
  basicUserRepo: BasicUserRepo,
  slackAnalytics: SlackAnalytics,
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

  def getSlackTeamOpt(slackTeamId: SlackTeamId): Option[SlackTeam] = db.readOnlyMaster(implicit s => slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId))

  def setupSlackTeam(userId: Id[User], slackTeamId: SlackTeamId, organizationId: Option[Id[Organization]])(implicit context: HeimdalContext): Future[SlackTeam] = {
    val (slackTeam, connectableOrgs) = db.readWrite { implicit session =>
      (slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId).get, getOrganizationsToConnectToSlackTeam(userId))
    }
    organizationId match {
      case Some(orgId) => Future.fromTry(connectSlackTeamToOrganization(userId, slackTeamId, orgId))
      case None if slackTeam.organizationId.isEmpty && connectableOrgs.isEmpty => createOrganizationForSlackTeam(userId, slackTeamId)
      case _ => Future.successful(slackTeam)
    }
  }

  def addSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(SlackTeam, Boolean)] = {
    val (teamOpt, connectableOrgs) = db.readOnlyMaster { implicit session =>
      (slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId), getOrganizationsToConnectToSlackTeam(userId))
    }
    teamOpt match {
      case Some(team) if team.organizationId.isEmpty && connectableOrgs.isEmpty => createOrganizationForSlackTeam(userId, slackTeamId).imap((_, true))
      case Some(team) => Future.successful((team, false))
      case None => Future.failed(SlackActionFail.TeamNotFound(slackTeamId))
    }
  }

  def createOrganizationForSlackTeam(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[SlackTeam] = {
    val (slackTeamOpt, membershipOpt) = db.readOnlyMaster { implicit session =>
      val slackTeamOpt = slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId)
      val membershipOpt = slackTeamMembershipRepo.getByUserIdAndSlackTeam(userId, slackTeamId)
      (slackTeamOpt, membershipOpt)
    }

    slackTeamOpt match {
      case Some(team) => team.organizationId match {
        case None => membershipOpt match {
          case Some(membership) =>
            val preferredTokens = Seq(team.getKifiBotTokenIncludingScopes(SlackAuthScope.teamSetup), membership.getTokenIncludingScopes(SlackAuthScope.teamSetup)).flatten
            slackClient.getTeamInfo(slackTeamId, preferredTokens).flatMap { teamInfo =>
              val orgInitialValues = OrganizationInitialValues(name = teamInfo.name.value, description = None)
              orgCommander.createOrganization(OrganizationCreateRequest(userId, orgInitialValues)) match {
                case Right(createdOrg) =>
                  val orgId = createdOrg.newOrg.id.get
                  val futureAvatar = teamInfo.icon.maxByOpt(_._1) match {
                    case None => Future.successful(())
                    case Some((_, imageUrl)) => orgAvatarCommander.persistRemoteOrganizationAvatars(orgId, imageUrl).imap(_ => ())
                  }
                  teamInfo.emailDomains.exists { domain =>
                    orgCommander.modifyOrganization(OrganizationModifyRequest(userId, orgId, OrganizationModifications(rawSite = Some(domain.value)))).isRight
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
          case None => Future.failed(SlackActionFail.InvalidMembership(userId, team.slackTeamId, team.slackTeamName, membershipOpt))
        }
        case Some(orgId) => Future.failed(SlackActionFail.TeamAlreadyConnected(team.slackTeamId, team.slackTeamName, orgId))
      }
      case None => Future.failed(SlackActionFail.TeamNotFound(slackTeamId))
    }
  }

  def connectSlackTeamToOrganization(userId: Id[User], slackTeamId: SlackTeamId, newOrganizationId: Id[Organization])(implicit context: HeimdalContext): Try[SlackTeam] = {
    db.readWrite { implicit session =>
      permissionCommander.getOrganizationPermissions(newOrganizationId, Some(userId)).contains(SlackIdentityCommander.slackSetupPermission) match {
        case false => Failure(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        case true => slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId) match {
          case None => Failure(SlackActionFail.TeamNotFound(slackTeamId))
          case Some(team) => team.organizationId match {
            case Some(connectedOrg) if connectedOrg == newOrganizationId => Success(team)
            case Some(otherOrg) => Failure(SlackActionFail.TeamAlreadyConnected(team.slackTeamId, team.slackTeamName, team.organizationId.get))
            case None => slackTeamRepo.getByOrganizationId(newOrganizationId) match {
              case Some(orgTeam) => Failure(SlackActionFail.OrgAlreadyConnected(newOrganizationId, orgTeam.slackTeamId, failedToConnectTeam = slackTeamId))
              case None => slackTeamMembershipRepo.getByUserIdAndSlackTeam(userId, slackTeamId) match {
                case None => Failure(SlackActionFail.InvalidMembership(userId, team.slackTeamId, team.slackTeamName, None))
                case Some(validMembership) => Success(slackTeamRepo.save(team.withOrganizationId(Some(newOrganizationId))))
              }
            }
          }
        }
      }
    } tap {
      case Success(team) => slackClient.getUsers(team.slackTeamId).imap(Some(_)).recover { case _ => None }.map { slackTeamMembersOpt =>
        val numMembersOpt = slackTeamMembersOpt.map(_.count(member => !member.deleted && !member.bot))
        val (user, org) = db.readOnlyMaster { implicit session =>
          (basicUserRepo.load(userId), organizationInfoCommander.getBasicOrganizationHelper(newOrganizationId))
        }
        inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
          user, "connected Slack team", team.slackTeamName.value, numMembersOpt.map(numMembers => s"with $numMembers members"), "to Kifi org", org
        )))
      }

      case Failure(fail) =>
        slackLog.warn(s"Failed to connect $slackTeamId to org $newOrganizationId for user $userId because:", fail.getMessage)
    }
  }

  private def setupSlackChannel(team: SlackTeam, membership: SlackTeamMembership, channel: SlackPublicChannelInfo)(implicit context: HeimdalContext): Either[LibraryFail, Library] = {
    require(membership.slackTeamId == team.slackTeamId, s"SlackTeam ${team.id.get}/${team.slackTeamId.value} doesn't match SlackTeamMembership ${membership.id.get}/${membership.slackTeamId.value}")
    require(membership.userId.isDefined, s"SlackTeamMembership ${membership.id.get} doesn't belong to any user.")

    val userId = membership.userId.get
    val libraryName = channel.channelName.value
    val librarySpace = LibrarySpace(userId, team.organizationId)

    // If this channel is the slack team's general channel, try to sync it with the org's general library
    // if not, just create a library as normal
    def createLibrary() = {
      val maybeOrgGeneralLibrary = if (channel.isGeneral) {
        val generalLib = db.readOnlyMaster { implicit s => libraryRepo.getBySpaceAndKind(librarySpace, LibraryKind.SYSTEM_ORG_GENERAL).headOption }
        generalLib.map { lib => libraryCommander.unsafeModifyLibrary(lib, LibraryModifications(name = Some(channel.channelName.value))).modifiedLibrary }
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
            slackChannelId = channel.channelId,
            slackChannelName = channel.channelName,
            status = SlackIntegrationStatus.On
          ))
          channelToLibRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
            requesterId = userId,
            space = librarySpace,
            libraryId = library.id.get,
            slackUserId = membership.slackUserId,
            slackTeamId = membership.slackTeamId,
            slackChannelId = channel.channelId,
            slackChannelName = channel.channelName,
            status = SlackIntegrationStatus.On
          ))
        }
    }
  }

  private def setupPublicSlackChannels(team: SlackTeam, membership: SlackTeamMembership, channels: Seq[SlackPublicChannelInfo])(implicit context: HeimdalContext): SlackChannelLibraries = {
    val markedAsSyncing = db.readWrite { implicit session => slackTeamRepo.markAsSyncing(team.slackTeamId, SlackTeamCommander.channelSyncTimeout) }

    if (markedAsSyncing) {

      val libCreationsByChannel = channels.map { channel =>
        channel.channelIdAndName -> setupSlackChannel(team, membership, channel)
      }.toMap

      val newLibraries = libCreationsByChannel.collect { case (channel, Right(lib)) => channel -> lib }
      val failedChannels = libCreationsByChannel.collect { case (channel, Left(fail)) => channel -> fail }

      val updatedTeam = team.withPublicChannelsSyncedAt(clock.now).withSyncedChannels(newLibraries.keySet.map(_.id))
      db.readWrite { implicit s => slackTeamRepo.save(updatedTeam) }

      if (failedChannels.nonEmpty) slackLog.warn(
        "Failed to create some libraries while integrating Slack team", team.slackTeamId.value, ".",
        "The errors are:", failedChannels.values.map(_.getMessage).mkString("[", ",", "]")
      )

      if (newLibraries.nonEmpty) {
        SafeFuture(inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
          "Created", newLibraries.size, "libraries from", team.slackTeamName.value, "channels",
          team.organizationId.map(orgId => DescriptionElements("for", db.readOnlyMaster { implicit s => organizationInfoCommander.getBasicOrganizationHelper(orgId) }))
        ))))
      }

      libCreationsByChannel
    } else Map.empty
  }

  def syncPublicChannels(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(Id[Organization], Set[SlackChannelIdAndName], Future[SlackChannelLibraries])] = {
    val teamOpt = db.readOnlyMaster { implicit session => slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId) }
    teamOpt match {
      case Some(team) =>
        team.organizationId match {
          case Some(orgId) =>
            val (membershipOpt, integratedChannelIds, hasOrgPermissions) = db.readOnlyMaster { implicit session =>
              val membershipOpt = slackTeamMembershipRepo.getByUserIdAndSlackTeam(userId, team.slackTeamId)
              val integratedChannelIds = channelToLibRepo.getIntegrationsByOrg(orgId).filter(_.slackTeamId == team.slackTeamId).map(_.slackChannelId).toSet
              val hasOrgPermissions = permissionCommander.getOrganizationPermissions(orgId, Some(userId)).contains(SlackIdentityCommander.slackSetupPermission)
              (membershipOpt, integratedChannelIds, hasOrgPermissions)
            }
            if (hasOrgPermissions) {
              membershipOpt match {
                case Some(membership) =>
                  val shouldSync = {
                    val now = clock.now()
                    val alreadySyncing = team.publicChannelsLastSyncingAt.exists(_ isAfter now.minusSeconds(SlackTeamCommander.channelSyncTimeout.toSeconds.toInt))
                    val syncedRecently = team.publicChannelsLastSyncedAt.exists(_ isAfter now.minusSeconds(SlackTeamCommander.channelSyncBuffer.toSeconds.toInt))
                    !alreadySyncing && !syncedRecently
                  }
                  if (shouldSync) {
                    val preferredTokens = Seq(team.getKifiBotTokenIncludingScopes(SlackAuthScope.syncPublicChannels), membership.getTokenIncludingScopes(SlackAuthScope.syncPublicChannels)).flatten
                    val onboardingAgent = slackOnboarder.getTeamAgent(team, membership)
                    onboardingAgent.intro().flatMap { _ =>
                      slackClient.getChannels(slackTeamId, excludeArchived = true, preferredTokens = preferredTokens).map { channels =>
                        val updatedTeam = {
                          val teamWithGeneral = channels.collectFirst { case channel if channel.isGeneral => team.withGeneralChannelId(channel.channelId) }
                          val updatedTeam = (teamWithGeneral getOrElse team).withSyncedChannels(integratedChannelIds) // lazy single integration channel backfilling
                          if (team == updatedTeam) team else db.readWrite { implicit session => slackTeamRepo.save(updatedTeam) }
                        }
                        def shouldBeIgnored(channel: SlackPublicChannelInfo) = channel.isArchived || updatedTeam.channelsSynced.contains(channel.channelId)
                        val channelsToIntegrate = channels.filter(!shouldBeIgnored(_)).sortBy(_.createdAt)
                        val futureSlackChannelLibraries = SafeFuture {
                          setupPublicSlackChannels(updatedTeam, membership, channelsToIntegrate)
                        } flatMap { slackChannelLibraries =>
                          onboardingAgent.channels(membership, channelsToIntegrate).map { _ =>
                            slackChannelLibraries
                          }
                        }
                        (orgId, channelsToIntegrate.map(_.channelIdAndName).toSet, futureSlackChannelLibraries)
                      }
                    }
                  } else {
                    Future.successful((orgId, Set.empty[SlackChannelIdAndName], Future.successful(Map.empty[SlackChannelIdAndName, Either[LibraryFail, Library]])))
                  }
                case None => Future.failed(SlackActionFail.InvalidMembership(userId, team.slackTeamId, team.slackTeamName, membershipOpt))
              }
            } else Future.failed(OrganizationFail.INSUFFICIENT_PERMISSIONS)

          case None => Future.failed(SlackActionFail.TeamNotConnected(team.slackTeamId, team.slackTeamName))
        }
      case None => Future.failed(SlackActionFail.TeamNotFound(slackTeamId))
    }
  }

  def getOrganizationsToConnectToSlackTeam(userId: Id[User])(implicit session: RSession): Set[BasicOrganization] = {
    val allOrgIds = orgMembershipRepo.getAllByUserId(userId).map(_.organizationId).toSet
    val permissionsByOrgIds = permissionCommander.getOrganizationsPermissions(allOrgIds, Some(userId))
    val slackTeamsByOrgId = slackTeamRepo.getByOrganizationIds(allOrgIds)
    val validOrgIds = allOrgIds.filter(orgId => slackTeamsByOrgId(orgId).isEmpty && permissionsByOrgIds(orgId).contains(SlackIdentityCommander.slackSetupPermission))
    organizationInfoCommander.getBasicOrganizations(validOrgIds).values.toSet
  }

  def turnCommentMirroring(userId: Id[User], slackTeamId: SlackTeamId, turnOn: Boolean): Try[Id[Organization]] = db.readWrite { implicit session =>
    slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId) match {
      case Some(team) =>
        team.organizationId match {
          case Some(orgId) =>
            val hasOrgPermissions = permissionCommander.getOrganizationPermissions(orgId, Some(userId)).contains(SlackIdentityCommander.slackSetupPermission)
            if (hasOrgPermissions) {
              val updatedSetting = if (turnOn) StaticFeatureSetting.ENABLED else StaticFeatureSetting.DISABLED
              orgCommander.unsafeSetAccountFeatureSettings(orgId, OrganizationSettings(Map(StaticFeature.SlackCommentMirroring -> updatedSetting)), Some(userId))
              Success(orgId)
            } else Failure(OrganizationFail.INSUFFICIENT_PERMISSIONS)

          case None => Failure(SlackActionFail.TeamNotConnected(team.slackTeamId, team.slackTeamName))
        }
      case None => Failure(SlackActionFail.TeamNotFound(slackTeamId))
    }
  }
  def togglePersonalDigests(slackTeamId: SlackTeamId, slackUserId: SlackUserId, turnOn: Boolean): Future[Unit] = {
    val now = clock.now
    val membershipOpt = db.readWrite { implicit s =>
      slackTeamMembershipRepo.getBySlackTeamAndUser(slackTeamId, slackUserId).map { membership =>
        if (turnOn && membership.nextPersonalDigestAt.isEmpty) {
          slackTeamMembershipRepo.save(membership.withNextPersonalDigestAt(now))
        } else if (!turnOn && membership.nextPersonalDigestAt.isDefined) {
          slackTeamMembershipRepo.save(membership.withNoNextPersonalDigest)
        } else membership
      }
    }
    import DescriptionElements._
    membershipOpt.fold(Future.failed[Unit](SlackFail.NoSuchMembership(slackTeamId, slackUserId))) { membership =>
      val channelId = membership.slackUserId.asChannel
      val trackingParams = SlackAnalytics.generateTrackingParams(channelId, NotificationCategory.NonUser.SETTINGS_TOGGLE, Some((!turnOn).toString))
      val toggleLink = LinkElement(pathCommander.slackPersonalDigestToggle(slackTeamId, slackUserId, turnOn = false).withQuery(trackingParams))
      slackClient.sendToSlackHoweverPossible(membership.slackTeamId, channelId, SlackMessageRequest.fromKifi(DescriptionElements.formatForSlack(
        if (turnOn) DescriptionElements(
          ":tada: Thanks for having me back! I'll gather some of your stats and update you about once a week if I have things to share.",
          "If you want to power me back down, you can silence me", "here" --> toggleLink, "."
        )
        else DescriptionElements(
          ":+1: Roger that, I'll keep quiet from here on out.",
          "If you'd like to hear from me again, you can power my notifications back on", "here" --> toggleLink, "."
        )
      ))).map(_ => ())
    }
  }
}
