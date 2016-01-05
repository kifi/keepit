package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.core._
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.social.BasicUserRepo
import com.keepit.model.ExternalLibrarySpace.{ ExternalOrganizationSpace, ExternalUserSpace }
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.SlackAuthenticatedAction.{ SetupLibraryIntegrations, TurnOnChannelIngestion, TurnOnLibraryPush }
import com.keepit.slack.models._
import com.kifi.macros.json

@json
case class LibraryToSlackIntegrationInfo(
  id: PublicId[LibraryToSlackChannel],
  status: SlackIntegrationStatus,
  authLink: Option[String])

@json
case class SlackToLibraryIntegrationInfo(
  id: PublicId[SlackChannelToLibrary],
  status: SlackIntegrationStatus,
  authLink: Option[String])

@json
case class LibrarySlackIntegrationInfo(
  teamName: SlackTeamName,
  channelName: SlackChannelName,
  creatorName: SlackUsername,
  space: ExternalLibrarySpace,
  toSlack: Option[LibraryToSlackIntegrationInfo],
  fromSlack: Option[SlackToLibraryIntegrationInfo])

@json
case class LibrarySlackInfo(
  link: String,
  integrations: Seq[LibrarySlackIntegrationInfo])

object SlackInfoCommander {
  val slackSetupPermission = OrganizationPermission.EDIT_ORGANIZATION
}

@ImplementedBy(classOf[SlackInfoCommanderImpl])
trait SlackInfoCommander {
  // For use in the LibraryInfoCommander to send info down to clients
  def getSlackIntegrationsForLibraries(userId: Id[User], libraryIds: Set[Id[Library]]): Map[Id[Library], LibrarySlackInfo]
  def getIntegrationsBySlackChannel(teamId: SlackTeamId, channelId: SlackChannelId): SlackChannelIntegrations
}

@Singleton
class SlackInfoCommanderImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  basicUserRepo: BasicUserRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  implicit val publicIdConfiguration: PublicIdConfiguration)
    extends SlackInfoCommander with Logging {

  @StatsdTiming("SlackInfoCommander.getSlackIntegrationsForLibraries")
  def getSlackIntegrationsForLibraries(viewerId: Id[User], libraryIds: Set[Id[Library]]): Map[Id[Library], LibrarySlackInfo] = {
    db.readOnlyMaster { implicit session =>
      val userOrgs = orgMembershipRepo.getAllByUserId(viewerId).map(_.organizationId).toSet
      val slackToLibs = channelToLibRepo.getUserVisibleIntegrationsForLibraries(viewerId, userOrgs, libraryIds)
      val libToSlacks = libToChannelRepo.getUserVisibleIntegrationsForLibraries(viewerId, userOrgs, libraryIds)

      val teamMembershipMap = {
        val slackUserTeamPairs = slackToLibs.map(stl => (stl.slackUserId, stl.slackTeamId)).toSet ++ libToSlacks.map(lts => (lts.slackUserId, lts.slackTeamId)).toSet
        slackUserTeamPairs.flatMap {
          case k @ (slackUserId, slackTeamId) => slackTeamMembershipRepo.getBySlackTeamAndUser(slackTeamId, slackUserId).map { v => k -> v }
        }.toMap
      }
      val teamNameByTeamId = teamMembershipMap.map { // intentionally drops duplicates; hopefully they are all the same team name
        case ((_, slackTeamId), stm) => slackTeamId -> stm.slackTeamName
      }
      val externalSpaceBySpace: Map[LibrarySpace, ExternalLibrarySpace] = {
        (slackToLibs.map(_.space) ++ libToSlacks.map(_.space)).map {
          case space @ OrganizationSpace(orgId) => space -> ExternalOrganizationSpace(Organization.publicId(orgId))
          case space @ UserSpace(userId) => space -> ExternalUserSpace(basicUserRepo.load(userId).externalId)
        }.toMap
      }

      case class SlackIntegrationInfoKey(space: LibrarySpace, slackUserId: SlackUserId, slackTeamId: SlackTeamId, slackUsername: SlackUsername, slackChannelName: SlackChannelName)
      object SlackIntegrationInfoKey {
        def fromSTL(stl: SlackChannelToLibrary) = {
          val username = teamMembershipMap(stl.slackUserId, stl.slackTeamId).slackUsername
          SlackIntegrationInfoKey(stl.space, stl.slackUserId, stl.slackTeamId, username, stl.slackChannelName)
        }
        def fromLTS(lts: LibraryToSlackChannel) = {
          val username = teamMembershipMap(lts.slackUserId, lts.slackTeamId).slackUsername
          SlackIntegrationInfoKey(lts.space, lts.slackUserId, lts.slackTeamId, username, lts.slackChannelName)
        }
      }
      libraryIds.map { libId =>
        val fromSlacksThisLib = slackToLibs.filter(_.libraryId == libId)
        val toSlacksThisLib = libToSlacks.filter(_.libraryId == libId)

        val fromSlackGroupedInfos = fromSlacksThisLib.groupBy(SlackIntegrationInfoKey.fromSTL).map {
          case (key, Seq(fs)) =>
            val fsId = SlackChannelToLibrary.publicId(fs.id.get)
            val authLink = {
              val existingScopes = teamMembershipMap.get(fs.slackUserId, fs.slackTeamId).toSet[SlackTeamMembership].flatMap(_.scopes)
              val requiredScopes = SlackAuthScope.ingest
              if (requiredScopes subsetOf existingScopes) None
              else Some(SlackAPI.OAuthAuthorize(requiredScopes, TurnOnChannelIngestion -> fsId, Some(fs.slackTeamId)).url)
            }
            key -> SlackToLibraryIntegrationInfo(fsId, fs.status, authLink)
        }
        val toSlackGroupedInfos = toSlacksThisLib.groupBy(SlackIntegrationInfoKey.fromLTS).map {
          case (key, Seq(ts)) =>
            val tsId = LibraryToSlackChannel.publicId(ts.id.get)
            val authLink = {
              val hasValidWebhook = slackIncomingWebhookInfoRepo.getForIntegration(ts).nonEmpty
              lazy val existingScopes = teamMembershipMap.get(ts.slackUserId, ts.slackTeamId).toSet[SlackTeamMembership].flatMap(_.scopes)
              val requiredScopes = SlackAuthScope.push
              if (hasValidWebhook && (requiredScopes subsetOf existingScopes)) None
              else Some(SlackAPI.OAuthAuthorize(requiredScopes, TurnOnLibraryPush -> tsId, Some(ts.slackTeamId)).url)
            }
            key -> LibraryToSlackIntegrationInfo(tsId, ts.status, authLink)
        }
        val integrations = (fromSlackGroupedInfos.keySet ++ toSlackGroupedInfos.keySet).map { key =>
          LibrarySlackIntegrationInfo(
            teamName = teamNameByTeamId(key.slackTeamId),
            channelName = key.slackChannelName,
            creatorName = key.slackUsername,
            space = externalSpaceBySpace(key.space),
            toSlack = toSlackGroupedInfos.get(key),
            fromSlack = fromSlackGroupedInfos.get(key)
          )
        }.toSeq.sortBy(x => (x.teamName.value, x.channelName.value, x.creatorName.value))
        libId -> LibrarySlackInfo(
          link = SlackAPI.OAuthAuthorize(SlackAuthScope.push, SetupLibraryIntegrations -> Library.publicId(libId), None).url,
          integrations = integrations
        )

      }.toMap
    }
  }

  def getIntegrationsBySlackChannel(teamId: SlackTeamId, channelId: SlackChannelId): SlackChannelIntegrations = {
    db.readOnlyMaster { implicit session =>
      val channelToLibIntegrations = channelToLibRepo.getBySlackTeamAndChannel(teamId, channelId)
      val libToChannelIntegrations = libToChannelRepo.getBySlackTeamAndChannel(teamId, channelId)
      val integrationSpaces = (channelToLibIntegrations.map(stl => stl.libraryId -> stl.space) ++ libToChannelIntegrations.map(lts => lts.libraryId -> lts.space)).groupBy(_._1).mapValues(_.map(_._2).toSet)
      SlackChannelIntegrations(
        teamId = teamId,
        channelId = channelId,
        allLibraries = channelToLibIntegrations.map(_.libraryId).toSet ++ libToChannelIntegrations.map(_.libraryId),
        toLibraries = channelToLibIntegrations.collect { case integration if integration.status == SlackIntegrationStatus.On => integration.libraryId }.toSet,
        fromLibraries = libToChannelIntegrations.collect { case integration if integration.status == SlackIntegrationStatus.On => integration.libraryId }.toSet,
        spaces = integrationSpaces
      )
    }
  }
}
