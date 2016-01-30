package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.{ PermissionCommander, OrganizationInfoCommander }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.json.{ KeyFormat }
import com.keepit.common.logging.Logging
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.social.BasicUserRepo
import com.keepit.controllers.website.SlackController
import com.keepit.model.ExternalLibrarySpace.{ ExternalOrganizationSpace, ExternalUserSpace }
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.models._
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json.{ Writes, Json }

@json
case class LibraryToSlackIntegrationInfo(
  id: PublicId[LibraryToSlackChannel],
  status: SlackIntegrationStatus,
  authLink: Option[String],
  isMutable: Boolean)

@json
case class SlackToLibraryIntegrationInfo(
  id: PublicId[SlackChannelToLibrary],
  status: SlackIntegrationStatus,
  authLink: Option[String],
  isMutable: Boolean)

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

@json
case class OrganizationSlackTeamInfo(id: SlackTeamId, name: SlackTeamName, publicChannelsLastSyncedAt: Option[DateTime])

case class OrganizationSlackInfo(
  slackTeam: Option[OrganizationSlackTeamInfo],
  libraries: Seq[(BasicLibrary, LibrarySlackInfo)])

object OrganizationSlackInfo {
  private implicit val helperWrites = KeyFormat.key2Writes[BasicLibrary, LibrarySlackInfo]("library", "slack")
  implicit val writes: Writes[OrganizationSlackInfo] = Json.writes[OrganizationSlackInfo]
}

case class UserSlackInfo(memberships: Seq[UserSlackTeamInfo])

@json
case class UserSlackTeamInfo(teamId: SlackTeamId, orgId: Option[PublicId[Organization]], teamName: SlackTeamName, slackUserId: SlackUserId, username: SlackUsername)

object UserSlackInfo {
  def empty = UserSlackInfo(memberships = Seq.empty)
  implicit val writes: Writes[UserSlackInfo] = Json.writes[UserSlackInfo]
}

object SlackInfoCommander {
  val slackSetupPermission = OrganizationPermission.EDIT_ORGANIZATION
}

@ImplementedBy(classOf[SlackInfoCommanderImpl])
trait SlackInfoCommander {
  // For use in the LibraryInfoCommander to send info down to clients
  def getSlackIntegrationsForLibraries(userId: Id[User], libraryIds: Set[Id[Library]]): Map[Id[Library], LibrarySlackInfo]

  // Called by ShoeboxServiceController
  def getIntegrationsBySlackChannel(teamId: SlackTeamId, channelId: SlackChannelId): SlackChannelIntegrations

  // For generating OrganizationInfo
  def getOrganizationSlackInfo(orgId: Id[Organization], viewerId: Id[User])(implicit session: RSession): OrganizationSlackInfo

  // For generating UserProfileStats
  def getUserSlackInfo(userId: Id[User], viewerId: Option[Id[User]])(implicit session: RSession): UserSlackInfo
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
  libRepo: LibraryRepo,
  orgInfoCommander: OrganizationInfoCommander,
  slackStateCommander: SlackAuthStateCommander,
  permissionCommander: PermissionCommander,
  slackCommander: SlackCommander,
  implicit val publicIdConfiguration: PublicIdConfiguration)
    extends SlackInfoCommander with Logging {

  @StatsdTiming("SlackInfoCommander.getSlackIntegrationsForLibraries")
  def getSlackIntegrationsForLibraries(viewerId: Id[User], libraryIds: Set[Id[Library]]): Map[Id[Library], LibrarySlackInfo] = {
    val integrationInfosByLib = db.readOnlyMaster { implicit session =>
      val userOrgs = orgMembershipRepo.getAllByUserId(viewerId).map(_.organizationId).toSet
      val slackToLibs = channelToLibRepo.getUserVisibleIntegrationsForLibraries(viewerId, userOrgs, libraryIds)
      val libToSlacks = libToChannelRepo.getUserVisibleIntegrationsForLibraries(viewerId, userOrgs, libraryIds)
      generateLibrarySlackIntegrationInfos(viewerId, slackToLibs, libToSlacks)
    }
    assembleLibrarySlackInfos(libraryIds, integrationInfosByLib)
  }

  private def assembleLibrarySlackInfos(libraryIds: Set[Id[Library]], integrationInfosByLib: Map[Id[Library], Seq[LibrarySlackIntegrationInfo]]): Map[Id[Library], LibrarySlackInfo] = {
    libraryIds.map { libId =>
      val pubLibId = Library.publicId(libId)
      libId -> LibrarySlackInfo(
        link = com.keepit.controllers.website.routes.SlackController.setupLibraryIntegrations(pubLibId).url,
        integrations = integrationInfosByLib.getOrElse(libId, Seq.empty)
      )
    }.toMap
  }

  private def generateLibrarySlackIntegrationInfos(viewerId: Id[User], slackToLibs: Seq[SlackChannelToLibrary], libToSlacks: Seq[LibraryToSlackChannel])(implicit session: RSession): Map[Id[Library], Seq[LibrarySlackIntegrationInfo]] = {
    val libraryIds = (slackToLibs.map(_.libraryId) ++ libToSlacks.map(_.libraryId)).toSet
    val permissionsByLib = permissionCommander.getLibrariesPermissions(libraryIds, Some(viewerId))
    val teamMembershipMap = {
      val slackUserTeamPairs = slackToLibs.map(stl => (stl.slackUserId, stl.slackTeamId)).toSet ++ libToSlacks.map(lts => (lts.slackUserId, lts.slackTeamId)).toSet
      slackUserTeamPairs.flatMap {
        case k @ (slackUserId, slackTeamId) => slackTeamMembershipRepo.getBySlackTeamAndUser(slackTeamId, slackUserId).map { v => k -> v }
      }.toMap
    }
    val teamNameByTeamId = teamMembershipMap.map {
      // intentionally drops duplicates; hopefully they are all the same team name
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
      val permissions = permissionsByLib.getOrElse(libId, Set.empty)
      val publicLibId = Library.publicId(libId)
      val fromSlacksThisLib = slackToLibs.filter(_.libraryId == libId)
      val toSlacksThisLib = libToSlacks.filter(_.libraryId == libId)

      val fromSlackGroupedInfos = fromSlacksThisLib.groupBy(SlackIntegrationInfoKey.fromSTL).map {
        case (key, Seq(fs)) =>
          val fsPubId = SlackChannelToLibrary.publicId(fs.id.get)
          val authLink = {
            // todo(Léo): kill this authLink and have the front-end always call SlackController.turnOnChannelIngestion
            val existingScopes = teamMembershipMap.get(fs.slackUserId, fs.slackTeamId).flatMap(_.tokenWithScopes.map(_.scopes)) getOrElse Set.empty
            val missingScopes = TurnOnChannelIngestion(fs.id.get.id).getMissingScopes(existingScopes)
            if (missingScopes.isEmpty) None
            else Some(com.keepit.controllers.website.routes.SlackController.turnOnChannelIngestion(publicLibId, fsPubId.id).url)
          }
          key -> SlackToLibraryIntegrationInfo(fsPubId, fs.status, authLink, isMutable = permissions.contains(LibraryPermission.ADD_KEEPS))
      }
      val toSlackGroupedInfos = toSlacksThisLib.groupBy(SlackIntegrationInfoKey.fromLTS).map {
        case (key, Seq(ts)) =>
          val tsPubId = LibraryToSlackChannel.publicId(ts.id.get)
          val authLink = {
            // todo(Léo): kill this authLink and have the front-end always call SlackController.turnOnLibraryPush
            lazy val existingScopes = teamMembershipMap.get(ts.slackUserId, ts.slackTeamId).flatMap(_.tokenWithScopes.map(_.scopes)) getOrElse Set.empty
            val missingScopes = TurnOnLibraryPush(ts.id.get.id).getMissingScopes(existingScopes)
            if (missingScopes.isEmpty) None
            else Some(com.keepit.controllers.website.routes.SlackController.turnOnLibraryPush(publicLibId, tsPubId.id).url)
          }
          key -> LibraryToSlackIntegrationInfo(tsPubId, ts.status, authLink, isMutable = permissions.contains(LibraryPermission.VIEW_LIBRARY))
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

      libId -> integrations
    }.toMap
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

  def getOrganizationSlackInfo(orgId: Id[Organization], viewerId: Id[User])(implicit session: RSession): OrganizationSlackInfo = {
    val (libIds, basicLibsById, integrationInfosByLib, slackTeams) = {
      val slackToLibs = channelToLibRepo.getIntegrationsByOrg(orgId)
      val libToSlacks = libToChannelRepo.getIntegrationsByOrg(orgId)
      val libIds = (slackToLibs.map(_.libraryId) ++ libToSlacks.map(_.libraryId)).toSet

      val basicLibsById = {
        val libs = libRepo.getActiveByIds(libIds).values.toList
        val owners = libs.map(_.ownerId).toSet
        val basicUserById = basicUserRepo.loadAll(owners)
        val orgIds = libs.flatMap(_.organizationId).toSet
        val basicOrgs = orgIds.flatMap { orgId => orgInfoCommander.getBasicOrganizationHelper(orgId).map(orgId -> _) }.toMap
        libs.map { lib =>
          lib.id.get -> BasicLibrary(lib, basicUserById(lib.ownerId), lib.organizationId.flatMap(basicOrgs.get).map(_.handle))
        }.toMap
      }
      val integrationInfosByLib = generateLibrarySlackIntegrationInfos(viewerId, slackToLibs, libToSlacks)
      val slackTeams = slackTeamRepo.getByOrganizationId(orgId).map(team => OrganizationSlackTeamInfo(team.slackTeamId, team.slackTeamName, team.publicChannelsLastSyncedAt))

      (libIds, basicLibsById, integrationInfosByLib, slackTeams)
    }

    val permissionsByLib = permissionCommander.getLibrariesPermissions(libIds, Some(viewerId))
    def canViewLib(libId: Id[Library]): Boolean = permissionsByLib.getOrElse(libId, Set.empty).contains(LibraryPermission.VIEW_LIBRARY)

    val slackTeam = slackTeams.headOption
    val librarySlackInfosByLib = assembleLibrarySlackInfos(libIds, integrationInfosByLib)
    OrganizationSlackInfo(
      slackTeam,
      libraries = libIds.toList.sorted.collect {
        case libId if canViewLib(libId) => (basicLibsById(libId), librarySlackInfosByLib(libId))
      }
    )
  }

  def getUserSlackInfo(userId: Id[User], viewerId: Option[Id[User]])(implicit session: RSession): UserSlackInfo = {
    if (!viewerId.contains(userId)) UserSlackInfo.empty
    else {
      val memberships = slackTeamMembershipRepo.getByUserId(userId)
      val userSlackTeamInfos = memberships.map { stm =>
        val orgIdOpt = slackTeamRepo.getBySlackTeamId(stm.slackTeamId).flatMap(_.organizationId)
        UserSlackTeamInfo(stm.slackTeamId, orgIdOpt.map(Organization.publicId), stm.slackTeamName, stm.slackUserId, stm.slackUsername)
      }
      UserSlackInfo(userSlackTeamInfos)
    }
  }
}
