package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.{ PermissionCommander, OrganizationInfoCommander, LibraryCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.SlackLog
import com.keepit.common.time.Clock
import com.keepit.common.util.DescriptionElements
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.slack.SlackChannelCommander.SlackChannelLibraries
import com.keepit.slack.models._
import org.joda.time.Duration
import com.keepit.common.core._
import com.keepit.common.time._

import scala.concurrent.{ ExecutionContext, Future }

object SlackChannelCommander {
  type SlackChannelLibraries = Map[SlackChannelIdAndName, Either[LibraryFail, Library]]
  val channelSyncTimeout = Duration.standardMinutes(20)
  val channelSyncBuffer = Duration.standardSeconds(10)
}

@ImplementedBy(classOf[SlackChannelCommanderImpl])
trait SlackChannelCommander {
  def syncPublicChannels(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(Id[Organization], Set[SlackChannelIdAndName], Future[SlackChannelLibraries])]
  def syncPrivateChannels(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(Id[Organization], Set[SlackChannelIdAndName], Future[SlackChannelLibraries])]
}

@Singleton
class SlackChannelCommanderImpl @Inject() (
    db: Database,
    organizationInfoCommander: OrganizationInfoCommander,
    permissionCommander: PermissionCommander,
    libraryRepo: LibraryRepo,
    libraryCommander: LibraryCommander,
    slackTeamRepo: SlackTeamRepo,
    slackTeamMembershipRepo: SlackTeamMembershipRepo,
    channelRepo: SlackChannelRepo,
    channelToLibRepo: SlackChannelToLibraryRepo,
    libToChannelRepo: LibraryToSlackChannelRepo,
    slackClient: SlackClientWrapper,
    slackOnboarder: SlackOnboarder,
    slackAnalytics: SlackAnalytics,
    clock: Clock,
    implicit val executionContext: ExecutionContext,
    implicit val inhouseSlackClient: InhouseSlackClient) extends SlackChannelCommander {

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)

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
                    val alreadySyncing = team.channelsLastSyncingAt.exists(_ isAfter (now minus SlackChannelCommander.channelSyncTimeout))
                    val syncedRecently = team.publicChannelsLastSyncedAt.exists(_ isAfter (now minus SlackChannelCommander.channelSyncBuffer))
                    !alreadySyncing && !syncedRecently
                  }
                  if (shouldSync) {
                    val preferredTokens = Seq(team.getKifiBotTokenIncludingScopes(SlackAuthScope.syncPublicChannels), membership.getTokenIncludingScopes(SlackAuthScope.syncPublicChannels)).flatten
                    val onboardingAgent = slackOnboarder.getTeamAgent(team, membership)
                    onboardingAgent.syncingPublicChannels().flatMap { _ =>
                      slackClient.getPublicChannels(slackTeamId, excludeArchived = true, preferredTokens = preferredTokens).map { channels =>
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
                          onboardingAgent.syncedPublicChannels(channelsToIntegrate).map { _ =>
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

  def syncPrivateChannels(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(Id[Organization], Set[SlackChannelIdAndName], Future[SlackChannelLibraries])] = {
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
              val tokenOpt = membershipOpt.flatMap(_.getTokenIncludingScopes(Set(SlackAuthScope.GroupsRead)))
              (membershipOpt, tokenOpt) match {
                case (Some(membership), Some(token)) =>
                  val shouldSync = {
                    val now = clock.now()
                    val alreadySyncing = team.channelsLastSyncingAt.exists(_ isAfter (now minus SlackChannelCommander.channelSyncTimeout))
                    val syncedRecently = membership.privateChannelsLastSyncedAt.exists(_ isAfter (now minus SlackChannelCommander.channelSyncBuffer))
                    !alreadySyncing && !syncedRecently
                  }
                  if (shouldSync) {
                    val onboardingAgent = slackOnboarder.getTeamAgent(team, membership)
                    onboardingAgent.syncingPrivateChannels().flatMap { _ =>
                      slackClient.getPrivateChannels(token).map { channels =>
                        val updatedTeam = {
                          val updatedTeam = team.withSyncedChannels(integratedChannelIds) // lazy single integration channel backfilling
                          if (team == updatedTeam) team else db.readWrite { implicit session => slackTeamRepo.save(updatedTeam) }
                        }
                        def shouldBeIgnored(channel: SlackPrivateChannelInfo) = channel.isArchived || channel.isMultipartyDM || updatedTeam.channelsSynced.contains(channel.channelId)
                        val channelsToIntegrate = channels.filter(!shouldBeIgnored(_)).sortBy(_.createdAt)
                        val futureSlackChannelLibraries = SafeFuture {
                          setupPrivateSlackChannels(updatedTeam, membership, channelsToIntegrate)
                        } flatMap { slackChannelLibraries =>
                          onboardingAgent.syncedPrivateChannels(channelsToIntegrate).map { _ =>
                            slackChannelLibraries
                          }
                        }
                        (orgId, channelsToIntegrate.map(_.channelIdAndName).toSet, futureSlackChannelLibraries)
                      }
                    }
                  } else {
                    Future.successful((orgId, Set.empty[SlackChannelIdAndName], Future.successful(Map.empty[SlackChannelIdAndName, Either[LibraryFail, Library]])))
                  }
                case _ => Future.failed(SlackActionFail.InvalidMembership(userId, team.slackTeamId, team.slackTeamName, membershipOpt))
              }
            } else Future.failed(OrganizationFail.INSUFFICIENT_PERMISSIONS)

          case None => Future.failed(SlackActionFail.TeamNotConnected(team.slackTeamId, team.slackTeamName))
        }
      case None => Future.failed(SlackActionFail.TeamNotFound(slackTeamId))
    }
  }

  private def setupPublicSlackChannels(team: SlackTeam, membership: SlackTeamMembership, channels: Seq[SlackPublicChannelInfo])(implicit context: HeimdalContext): SlackChannelLibraries = {
    val markedAsSyncing = db.readWrite { implicit session => slackTeamRepo.markAsSyncingChannels(team.slackTeamId, SlackChannelCommander.channelSyncTimeout) }

    if (markedAsSyncing) {

      val libCreationsByChannel = channels.map { channel =>
        channel.channelIdAndName -> setupSlackChannel(team, membership, channel)
      }.toMap

      val newLibraries = libCreationsByChannel.collect { case (channel, Right(lib)) => channel -> lib }
      val failedChannels = libCreationsByChannel.collect { case (channel, Left(fail)) => channel -> fail }

      db.readWrite { implicit s =>
        slackTeamRepo.save(slackTeamRepo.get(team.id.get).withPublicChannelsSyncedAt(clock.now).withSyncedChannels(newLibraries.keySet.map(_.id)))
      }

      if (failedChannels.nonEmpty) slackLog.warn(
        "Failed to create some libraries while integrating Slack team", team.slackTeamId.value, ".",
        "The errors are:", failedChannels.values.map(_.getMessage).mkString("[", ",", "]")
      )

      if (newLibraries.nonEmpty) {
        SafeFuture(inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
          "Created", newLibraries.size, "libraries from", team.slackTeamName.value, "public channels",
          team.organizationId.map(orgId => DescriptionElements("for", db.readOnlyMaster { implicit s => organizationInfoCommander.getBasicOrganizationHelper(orgId) }))
        ))))
      }

      libCreationsByChannel
    } else Map.empty
  }

  private def setupPrivateSlackChannels(team: SlackTeam, membership: SlackTeamMembership, channels: Seq[SlackPrivateChannelInfo])(implicit context: HeimdalContext): SlackChannelLibraries = {

    val markedAsSyncing = db.readWrite { implicit session => slackTeamRepo.markAsSyncingChannels(team.slackTeamId, SlackChannelCommander.channelSyncTimeout) }

    if (markedAsSyncing) {
      val libCreationsByChannel = channels.map { channel =>
        channel.channelIdAndName -> setupSlackChannel(team, membership, channel)
      }.toMap

      val newLibraries = libCreationsByChannel.collect { case (channel, Right(lib)) => channel -> lib }
      val failedChannels = libCreationsByChannel.collect { case (channel, Left(fail)) => channel -> fail }

      db.readWrite { implicit s =>
        slackTeamRepo.save(slackTeamRepo.get(team.id.get).withSyncedChannels(newLibraries.keySet.map(_.id)))
        slackTeamMembershipRepo.save(slackTeamMembershipRepo.get(membership.id.get).withPrivateChannelsSyncedAt(clock.now()))
      }

      if (failedChannels.nonEmpty) slackLog.warn(
        "Failed to create some libraries while integrating private channels from", membership.slackUserId.value, "in Slack team", team.slackTeamId.value, ".",
        "The errors are:", failedChannels.values.map(_.getMessage).mkString("[", ",", "]")
      )

      if (newLibraries.nonEmpty) {
        SafeFuture(inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
          "Created", newLibraries.size, "private libraries from", team.slackTeamName.value, "private channels",
          team.organizationId.map(orgId => DescriptionElements("for", db.readOnlyMaster { implicit s => organizationInfoCommander.getBasicOrganizationHelper(orgId) }))
        ))))
      }

      libCreationsByChannel
    } else Map.empty
  }

  private def setupSlackChannel(team: SlackTeam, membership: SlackTeamMembership, channel: SlackChannelInfo)(implicit context: HeimdalContext): Either[LibraryFail, Library] = {
    require(membership.slackTeamId == team.slackTeamId, s"SlackTeam ${team.id.get}/${team.slackTeamId.value} doesn't match SlackTeamMembership ${membership.id.get}/${membership.slackTeamId.value}")
    require(membership.userId.isDefined, s"SlackTeamMembership ${membership.id.get} doesn't belong to any user.")
    require(team.organizationId.isDefined, s"SlackTeam ${team.id.get} doesn't belong to any org.")

    val userId = membership.userId.get
    val orgId = team.organizationId.get

    val libraryMaybe = channel match {
      case publicChannel: SlackPublicChannelInfo => createLibraryForPublicChannel(orgId, userId, publicChannel)
      case privateChannel: SlackPrivateChannelInfo => createLibraryForPrivateChannel(orgId, userId, privateChannel)
    }

    libraryMaybe tap {
      case Left(_) =>
      case Right(library) =>
        db.readWrite { implicit session =>
          channelRepo.getOrCreate(team.slackTeamId, channel.channelId, channel.channelName)
          libToChannelRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
            requesterId = userId,
            space = library.space,
            libraryId = library.id.get,
            slackUserId = membership.slackUserId,
            slackTeamId = membership.slackTeamId,
            slackChannelId = channel.channelId,
            slackChannelName = channel.channelName,
            status = SlackIntegrationStatus.On
          ))
          channelToLibRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
            requesterId = userId,
            space = library.space,
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

  private def createLibraryForPublicChannel(organizationId: Id[Organization], userId: Id[User], channel: SlackPublicChannelInfo)(implicit context: HeimdalContext): Either[LibraryFail, Library] = {
    // If this channel is the slack team's general channel, try to sync it with the org's general library
    // if not, just create a library as normal
    val librarySpace = LibrarySpace.fromOrganizationId(organizationId)

    val maybeOrgGeneralLibrary = if (channel.isGeneral) {
      val generalLib = db.readOnlyMaster { implicit s => libraryRepo.getBySpaceAndKind(librarySpace, LibraryKind.SYSTEM_ORG_GENERAL).headOption }
      generalLib.map { lib => libraryCommander.unsafeModifyLibrary(lib, LibraryModifications(name = Some(channel.channelName.value))).modifiedLibrary }
    } else None

    maybeOrgGeneralLibrary.map(Right(_)).getOrElse {
      val initialValues = LibraryInitialValues(
        name = channel.channelName.value,
        visibility = LibraryVisibility.ORGANIZATION,
        kind = Some(LibraryKind.SLACK_CHANNEL),
        description = channel.purpose.map(_.value) orElse channel.topic.map(_.value),
        space = Some(librarySpace)
      )
      libraryCommander.createLibrary(initialValues, userId)
    }
  }

  private def createLibraryForPrivateChannel(organizationId: Id[Organization], userId: Id[User], channel: SlackPrivateChannelInfo)(implicit context: HeimdalContext): Either[LibraryFail, Library] = {
    val initialValues = LibraryInitialValues(
      name = channel.channelName.value,
      visibility = LibraryVisibility.SECRET,
      kind = Some(LibraryKind.SLACK_CHANNEL),
      description = channel.purpose.map(_.value) orElse channel.topic.map(_.value),
      space = Some(organizationId)
    )
    libraryCommander.createLibrary(initialValues, userId)
  }
}
