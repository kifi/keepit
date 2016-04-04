package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.commanders.{ PermissionCommander, OrganizationInfoCommander, LibraryCommander }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.time.Clock
import com.keepit.common.util.DescriptionElements
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.slack.SlackChannelCommander.{ SlackPrivateChannelLibraries, SlackPublicChannelLibraries }
import com.keepit.slack.models._
import org.joda.time.Duration
import com.keepit.common.core._
import com.keepit.common.time._

import scala.concurrent.{ ExecutionContext, Future }

object SlackChannelCommander {
  type SlackPublicChannelLibraries = Map[SlackPublicChannelInfo, Either[LibraryFail, Library]]
  type SlackPrivateChannelLibraries = Map[SlackPrivateChannelInfo, Either[LibraryFail, Library]]
  val channelSyncTimeout = Duration.standardMinutes(20)
  val channelSyncBuffer = Duration.standardSeconds(10)
}

@ImplementedBy(classOf[SlackChannelCommanderImpl])
trait SlackChannelCommander {
  def syncPublicChannels(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(Id[Organization], Set[SlackPublicChannelInfo], Future[SlackPublicChannelLibraries])]
  def syncPrivateChannels(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(Id[Organization], Set[SlackPrivateChannelInfo], Future[SlackPrivateChannelLibraries])]
  def syncChannelMemberships(slackTeamId: SlackTeamId, slackUserId: SlackUserId): Future[Map[(SlackUserId, SlackChannelId), LibraryMembership]]
  def syncAllChannelMemberships(slackTeamId: SlackTeamId): Future[Map[(SlackUserId, SlackChannelId), LibraryMembership]]
}

@Singleton
class SlackChannelCommanderImpl @Inject() (
    db: Database,
    basicOrganizationGen: BasicOrganizationGen,
    permissionCommander: PermissionCommander,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryCommander: LibraryCommander,
    slackTeamRepo: SlackTeamRepo,
    slackTeamMembershipRepo: SlackTeamMembershipRepo,
    channelRepo: SlackChannelRepo,
    channelToLibRepo: SlackChannelToLibraryRepo,
    libToChannelRepo: LibraryToSlackChannelRepo,
    slackIntegrationCommander: SlackIntegrationCommander,
    slackClient: SlackClientWrapper,
    slackOnboarder: SlackOnboarder,
    slackAnalytics: SlackAnalytics,
    clock: Clock,
    implicit val executionContext: ExecutionContext,
    implicit val inhouseSlackClient: InhouseSlackClient) extends SlackChannelCommander with Logging {

  val slackLog = new SlackLog(InhouseSlackChannel.ENG_SLACK)

  def syncPublicChannels(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(Id[Organization], Set[SlackPublicChannelInfo], Future[SlackPublicChannelLibraries])] = {
    val teamOpt = db.readOnlyMaster { implicit session => slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId) }
    teamOpt match {
      case Some(team) =>
        team.organizationId match {
          case Some(orgId) =>
            val (membershipOpt, integratedChannelIds, hasOrgPermissions) = db.readOnlyMaster { implicit session =>
              val membershipOpt = slackTeamMembershipRepo.getByUserIdAndSlackTeam(userId, team.slackTeamId)
              val integratedChannelIds = getIntegratedChannelIds(slackTeamId, orgId)
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
                      slackClient.getPublicChannels(slackTeamId, excludeArchived = false, preferredTokens = preferredTokens).map { channels =>
                        val updatedTeam = {
                          val teamWithGeneral = channels.collectFirst { case channel if channel.isGeneral => team.withGeneralChannelId(channel.channelId) }
                          val updatedTeam = (teamWithGeneral getOrElse team).withSyncedChannels(integratedChannelIds) // lazy single integration channel backfilling
                          if (team == updatedTeam) team else db.readWrite { implicit session => slackTeamRepo.save(updatedTeam) }
                        }
                        def shouldBeIgnored(channel: SlackPublicChannelInfo) = channel.isArchived || updatedTeam.channelsSynced.contains(channel.channelId)
                        val toBeSetup = channels.filter(!shouldBeIgnored(_))
                        val futureSlackChannelLibraries = SafeFuture {
                          setupPublicSlackChannels(updatedTeam, membership, channels, toBeSetup)
                        } flatMap { slackChannelLibraries =>
                          val successes = toBeSetup.filter(channel => slackChannelLibraries.get(channel).exists(_.isRight))
                          onboardingAgent.syncedPublicChannels(successes).map { _ =>
                            slackChannelLibraries
                          }
                        }
                        (orgId, toBeSetup.toSet, futureSlackChannelLibraries)
                      }
                    }
                  } else {
                    Future.successful((orgId, Set.empty[SlackPublicChannelInfo], Future.successful(Map.empty[SlackPublicChannelInfo, Either[LibraryFail, Library]])))
                  }
                case None => Future.failed(SlackActionFail.InvalidMembership(userId, team.slackTeamId, team.slackTeamName, membershipOpt))
              }
            } else Future.failed(OrganizationFail.INSUFFICIENT_PERMISSIONS)

          case None => Future.failed(SlackActionFail.TeamNotConnected(team.slackTeamId, team.slackTeamName))
        }
      case None => Future.failed(SlackActionFail.TeamNotFound(slackTeamId))
    }
  }

  def syncPrivateChannels(userId: Id[User], slackTeamId: SlackTeamId)(implicit context: HeimdalContext): Future[(Id[Organization], Set[SlackPrivateChannelInfo], Future[SlackPrivateChannelLibraries])] = {
    val teamOpt = db.readOnlyMaster { implicit session => slackTeamRepo.getBySlackTeamIdNoCache(slackTeamId) }
    teamOpt match {
      case Some(team) =>
        team.organizationId match {
          case Some(orgId) =>
            val (membershipOpt, integratedChannelIds, hasOrgPermissions) = db.readOnlyMaster { implicit session =>
              val membershipOpt = slackTeamMembershipRepo.getByUserIdAndSlackTeam(userId, team.slackTeamId)
              val integratedChannelIds = getIntegratedChannelIds(slackTeamId, orgId)
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
                      slackClient.getPrivateChannels(token, excludeArchived = false).map { channels =>
                        val updatedTeam = {
                          val updatedTeam = team.withSyncedChannels(integratedChannelIds) // lazy single integration channel backfilling
                          if (team == updatedTeam) team else db.readWrite { implicit session => slackTeamRepo.save(updatedTeam) }
                        }
                        def shouldBeIgnored(channel: SlackPrivateChannelInfo) = channel.isArchived || channel.isMultipartyDM || updatedTeam.channelsSynced.contains(channel.channelId)
                        val toBeSetup = channels.filter(!shouldBeIgnored(_))
                        val futureSlackChannelLibraries = SafeFuture {
                          setupPrivateSlackChannels(updatedTeam, membership, channels, toBeSetup)
                        } flatMap { slackChannelLibraries =>
                          val successes = toBeSetup.filter(channel => slackChannelLibraries.get(channel).exists(_.isRight))
                          onboardingAgent.syncedPrivateChannels(successes).map { _ =>
                            slackChannelLibraries
                          }
                        }
                        (orgId, toBeSetup.toSet, futureSlackChannelLibraries)
                      }
                    }
                  } else {
                    Future.successful((orgId, Set.empty[SlackPrivateChannelInfo], Future.successful(Map.empty[SlackPrivateChannelInfo, Either[LibraryFail, Library]])))
                  }
                case _ => Future.failed(SlackActionFail.InvalidMembership(userId, team.slackTeamId, team.slackTeamName, membershipOpt))
              }
            } else Future.failed(OrganizationFail.INSUFFICIENT_PERMISSIONS)

          case None => Future.failed(SlackActionFail.TeamNotConnected(team.slackTeamId, team.slackTeamName))
        }
      case None => Future.failed(SlackActionFail.TeamNotFound(slackTeamId))
    }
  }

  private def getIntegratedChannelIds(slackTeamId: SlackTeamId, orgId: Id[Organization])(implicit session: RSession): Set[SlackChannelId] = {
    val integrations = channelToLibRepo.getBySlackTeam(slackTeamId).toSet
    val orgLibraryIds = libraryRepo.getActiveByIds(integrations.map(_.libraryId)).collect { case (libraryId, library) if library.organizationId.contains(orgId) => libraryId }.toSet
    integrations.collect { case integration if orgLibraryIds.contains(integration.libraryId) => integration.slackChannelId }
  }

  private def setupPublicSlackChannels(team: SlackTeam, membership: SlackTeamMembership, allChannels: Seq[SlackPublicChannelInfo], toBeSetup: Seq[SlackPublicChannelInfo])(implicit context: HeimdalContext): SlackPublicChannelLibraries = {
    val markedAsSyncing = db.readWrite { implicit session => slackTeamRepo.markAsSyncingChannels(team.slackTeamId, SlackChannelCommander.channelSyncTimeout) }

    if (markedAsSyncing) {

      val libCreationsByChannel = toBeSetup.sortBy(_.createdAt).map { channel =>
        channel -> setupSlackChannel(team, membership, channel)
      }.toMap

      val newLibraries = libCreationsByChannel.collect { case (channel, Right(lib)) => channel -> lib }
      val failedChannels = libCreationsByChannel.collect { case (channel, Left(fail)) => channel -> fail }

      db.readWrite { implicit s =>
        addChannelMembersToChannelLibraries(team.slackTeamId, allChannels) // update members of all public channels, new and existing
        slackTeamRepo.save(slackTeamRepo.get(team.id.get).doneSyncing.withPublicChannelsSyncedAt(clock.now).withSyncedChannels(newLibraries.keySet.map(_.channelId)))
      }

      if (failedChannels.nonEmpty) slackLog.warn(
        "Failed to create some libraries while integrating Slack team", team.slackTeamId.value, ".",
        "The errors are:", failedChannels.values.map(_.getMessage).mkString("[", ",", "]")
      )

      if (newLibraries.nonEmpty) {
        SafeFuture(inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
          "Created", newLibraries.size, "libraries from", team.slackTeamName.value, "public channels",
          team.organizationId.map(orgId => DescriptionElements("for", db.readOnlyMaster { implicit s => basicOrganizationGen.getBasicOrganizationHelper(orgId) }))
        ))))
      }

      libCreationsByChannel
    } else Map.empty
  }

  private def setupPrivateSlackChannels(team: SlackTeam, membership: SlackTeamMembership, allChannels: Seq[SlackPrivateChannelInfo], toBeSetup: Seq[SlackPrivateChannelInfo])(implicit context: HeimdalContext): SlackPrivateChannelLibraries = {

    val markedAsSyncing = db.readWrite { implicit session => slackTeamRepo.markAsSyncingChannels(team.slackTeamId, SlackChannelCommander.channelSyncTimeout) }

    if (markedAsSyncing) {
      val libCreationsByChannel = toBeSetup.sortBy(_.createdAt).map { channel =>
        channel -> setupSlackChannel(team, membership, channel)
      }.toMap

      val newLibraries = libCreationsByChannel.collect { case (channel, Right(lib)) => channel -> lib }
      val failedChannels = libCreationsByChannel.collect { case (channel, Left(fail)) => channel -> fail }

      db.readWrite { implicit s =>
        addChannelMembersToChannelLibraries(team.slackTeamId, allChannels) // update members of all private channels, new and existing
        slackTeamRepo.save(slackTeamRepo.get(team.id.get).doneSyncing.withSyncedChannels(newLibraries.keySet.map(_.channelId)))
        slackTeamMembershipRepo.save(slackTeamMembershipRepo.get(membership.id.get).withPrivateChannelsSyncedAt(clock.now()))
      }

      if (failedChannels.nonEmpty) slackLog.warn(
        "Failed to create some libraries while integrating private channels from", membership.slackUserId.value, "in Slack team", team.slackTeamId.value, ".",
        "The errors are:", failedChannels.values.map(_.getMessage).mkString("[", ",", "]")
      )

      if (newLibraries.nonEmpty) {
        SafeFuture(inhouseSlackClient.sendToSlack(InhouseSlackChannel.SLACK_ALERTS, SlackMessageRequest.inhouse(DescriptionElements(
          "Created", newLibraries.size, "private libraries from", team.slackTeamName.value, "private channels",
          team.organizationId.map(orgId => DescriptionElements("for", db.readOnlyMaster { implicit s => basicOrganizationGen.getBasicOrganizationHelper(orgId) }))
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
          // Adjust notification settings for the integrator if he's actually a member of the Slack channel
          libraryMembershipRepo.getWithLibraryIdAndUserId(library.id.get, userId).foreach { libraryMembership =>
            val updatedLibraryMembership = libraryMembership.copy(subscribedToUpdates = channel.members.contains(membership.slackUserId))
            if (updatedLibraryMembership != libraryMembership) libraryMembershipRepo.save(updatedLibraryMembership)
          }

          channelRepo.getOrCreate(team.slackTeamId, channel.channelId, channel.channelName)
          libToChannelRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
            requesterId = userId,
            libraryId = library.id.get,
            slackUserId = membership.slackUserId,
            slackTeamId = membership.slackTeamId,
            slackChannelId = channel.channelId,
            status = SlackIntegrationStatus.On
          ))
          channelToLibRepo.internBySlackTeamChannelAndLibrary(SlackIntegrationCreateRequest(
            requesterId = userId,
            libraryId = library.id.get,
            slackUserId = membership.slackUserId,
            slackTeamId = membership.slackTeamId,
            slackChannelId = channel.channelId,
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
        name = formatSlackChannelName(channel.channelName.value),
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
      name = formatSlackChannelName(channel.channelName.value),
      visibility = LibraryVisibility.SECRET,
      kind = Some(LibraryKind.SLACK_CHANNEL),
      description = channel.purpose.map(_.value) orElse channel.topic.map(_.value),
      space = Some(organizationId)
    )
    libraryCommander.createLibrary(initialValues, userId)
  }

  private def formatSlackChannelName(name: String): String = {
    name.replaceAll("[_\\-#@]", " ").split(" ").map(_.trim).filter(_.nonEmpty).map(s => s.capitalize).mkString(" ")
  }

  private def addChannelMembersToChannelLibraries(slackTeamId: SlackTeamId, channels: Seq[SlackChannelInfo])(implicit session: RWSession): Map[SlackChannelInfo, Set[LibraryMembership]] = {
    val membersByChannel = channels.map { channel =>
      channel -> channel.members.map { slackUserId =>
        (slackUserId, channel.channelId)
      }
    }.toMap
    val libraryMemberships = joinChannelLibraries(slackTeamId, membersByChannel.values.flatten.toSet)
    membersByChannel.mapValues(_.flatMap(libraryMemberships.get))
  }

  private def joinChannelLibraries(slackTeamId: SlackTeamId, channelMemberships: Set[(SlackUserId, SlackChannelId)])(implicit session: RWSession): Map[(SlackUserId, SlackChannelId), LibraryMembership] = {
    slackTeamRepo.getBySlackTeamId(slackTeamId).flatMap(_.organizationId) match {
      case Some(orgId) =>
        val (allSlackUserIds, allSlackChannelIds) = channelMemberships.unzip
        val membershipBySlackUserId = slackTeamMembershipRepo.getBySlackIdentities(allSlackUserIds.map((slackTeamId, _)))
        val integrationsByChannel = slackIntegrationCommander.getBySlackChannels(slackTeamId, allSlackChannelIds)
        val orgLibraries = libraryRepo.getBySpace(orgId).map(_.id.get)
        val memberWithLibraryMemberships: Set[((SlackUserId, SlackChannelId), LibraryMembership)] = for {
          member @ (slackUserId, slackChannelId) <- channelMemberships
          slackMembership <- membershipBySlackUserId.get((slackTeamId, slackUserId)).toIterable
          userId <- slackMembership.userId.toIterable
          integrations <- integrationsByChannel.get(slackChannelId).toIterable
          libraryId <- integrations.allLibraries if orgLibraries.contains(libraryId)
        } yield {
          val libraryMembership = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId, None) match {
            case Some(existingMembership) if existingMembership.isActive => existingMembership
            case inactiveMembershipOpt =>
              val newMembership = LibraryMembership(id = inactiveMembershipOpt.flatMap(_.id), libraryId = libraryId, userId = userId, access = LibraryAccess.READ_WRITE, subscribedToUpdates = true)
              libraryMembershipRepo.save(newMembership)
          }
          member -> libraryMembership
        }
        memberWithLibraryMemberships.toMap
      case None => Map.empty
    }
  }

  def syncChannelMemberships(slackTeamId: SlackTeamId, slackUserId: SlackUserId): Future[Map[(SlackUserId, SlackChannelId), LibraryMembership]] = {
    syncChannelMemberships(slackTeamId, Some(slackUserId))
  }

  def syncAllChannelMemberships(slackTeamId: SlackTeamId): Future[Map[(SlackUserId, SlackChannelId), LibraryMembership]] = {
    syncChannelMemberships(slackTeamId, None)
  }

  private def syncChannelMemberships(slackTeamId: SlackTeamId, slackUserIdOpt: Option[SlackUserId]) = {
    val futurePublicChannels = slackClient.getPublicChannels(slackTeamId, excludeArchived = false) recover { case _ => Seq.empty }
    val futurePrivateChannels = {
      val slackTokens = db.readOnlyMaster { implicit session =>
        slackTeamMembershipRepo.getBySlackTeam(slackTeamId).flatMap(_.getTokenIncludingScopes(Set(SlackAuthScope.GroupsRead)))
      }
      FutureHelpers.foldLeft(slackTokens)(Set.empty[SlackPrivateChannelInfo]) {
        case (channels, nextToken) =>
          val moreChannels = slackClient.getPrivateChannels(nextToken, excludeArchived = false) recover { case _ => Set.empty }
          moreChannels.imap(channels ++ _)
      }
    }
    for {
      publicChannels <- futurePublicChannels
      privateChannels <- futurePrivateChannels
    } yield {
      val allChannels = privateChannels ++ publicChannels
      val relevantMemberships: Set[(SlackUserId, SlackChannelId)] = slackUserIdOpt match {
        case Some(slackUserId) => allChannels.collect { case channel if channel.members.contains(slackUserId) => (slackUserId, channel.channelId) }
        case None => allChannels.flatMap { channel => channel.members.map(((_, channel.channelId))) }
      }
      db.readWrite { implicit session =>
        joinChannelLibraries(slackTeamId, relevantMemberships)
      }
    }
  }

}
