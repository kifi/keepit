package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.{ OrganizationInfoCommander, PermissionCommander }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.json.KeyFormat
import com.keepit.common.logging.Logging
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.social.BasicUserRepo
import com.keepit.model.ExternalLibrarySpace.{ ExternalOrganizationSpace, ExternalUserSpace }
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import com.keepit.slack.models._
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json.{ Json, Writes }

@json
case class LibraryToSlackIntegrationInfo(
  id: PublicId[LibraryToSlackChannel],
  status: SlackIntegrationStatus,
  isMutable: Boolean)

@json
case class SlackToLibraryIntegrationInfo(
  id: PublicId[SlackChannelToLibrary],
  status: SlackIntegrationStatus,
  isMutable: Boolean)

@json
case class LibrarySlackIntegrationInfo(
  teamName: SlackTeamName,
  channelName: SlackChannelName,
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

@ImplementedBy(classOf[SlackInfoCommanderImpl])
trait SlackInfoCommander {
  // For use in the LibraryInfoCommander to send info down to clients
  def getSlackIntegrationsForLibraries(userId: Id[User], libraryIds: Set[Id[Library]]): Map[Id[Library], LibrarySlackInfo]

  // For generating OrganizationInfo
  def getOrganizationSlackInfo(orgId: Id[Organization], viewerId: Id[User], max: Option[Int] = None)(implicit session: RSession): OrganizationSlackInfo
  def getOrganizationSlackTeam(orgId: Id[Organization], viewerId: Id[User])(implicit session: RSession): Option[OrganizationSlackTeamInfo]

  // For generating UserProfileStats
  def getUserSlackInfo(userId: Id[User], viewerId: Option[Id[User]])(implicit session: RSession): UserSlackInfo
}

@Singleton
class SlackInfoCommanderImpl @Inject() (
  db: Database,
  slackTeamRepo: SlackTeamRepo,
  slackTeamMembershipRepo: SlackTeamMembershipRepo,
  slackChannelRepo: SlackChannelRepo,
  slackIncomingWebhookInfoRepo: SlackIncomingWebhookInfoRepo,
  channelToLibRepo: SlackChannelToLibraryRepo,
  libToChannelRepo: LibraryToSlackChannelRepo,
  basicUserRepo: BasicUserRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  libRepo: LibraryRepo,
  orgInfoCommander: OrganizationInfoCommander,
  permissionCommander: PermissionCommander,
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
    val canUserJoinOrWritetoLib = permissionCommander.canJoinOrWriteToLibrary(viewerId, libraryIds)
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

    val channelNameByTeamAndChannelId = {
      val channelIds = slackToLibs.map(stl => (stl.slackTeamId, stl.slackChannelId)).toSet ++ libToSlacks.map(lts => (lts.slackTeamId, lts.slackChannelId))
      slackChannelRepo.getByChannelIds(channelIds).mapValues(_.slackChannelName)
    }

    case class SlackIntegrationInfoKey(space: LibrarySpace, slackTeamId: SlackTeamId, slackChannelId: SlackChannelId)
    object SlackIntegrationInfoKey {
      def fromSTL(stl: SlackChannelToLibrary) = SlackIntegrationInfoKey(stl.space, stl.slackTeamId, stl.slackChannelId)
      def fromLTS(lts: LibraryToSlackChannel) = SlackIntegrationInfoKey(lts.space, lts.slackTeamId, lts.slackChannelId)
    }
    libraryIds.map { libId =>
      val permissions = permissionsByLib.getOrElse(libId, Set.empty)
      val fromSlacksThisLib = slackToLibs.filter(_.libraryId == libId)
      val toSlacksThisLib = libToSlacks.filter(_.libraryId == libId)

      val fromSlackGroupedInfos = fromSlacksThisLib.groupBy(SlackIntegrationInfoKey.fromSTL).map {
        case (key, Seq(fs)) =>
          val fsPubId = SlackChannelToLibrary.publicId(fs.id.get)
          key -> SlackToLibraryIntegrationInfo(fsPubId, fs.status, isMutable = canUserJoinOrWritetoLib.getOrElse(libId, false))
      }
      val toSlackGroupedInfos = toSlacksThisLib.groupBy(SlackIntegrationInfoKey.fromLTS).map {
        case (key, Seq(ts)) =>
          val tsPubId = LibraryToSlackChannel.publicId(ts.id.get)
          key -> LibraryToSlackIntegrationInfo(tsPubId, ts.status, isMutable = permissions.contains(LibraryPermission.VIEW_LIBRARY))
      }
      val integrations = (fromSlackGroupedInfos.keySet ++ toSlackGroupedInfos.keySet).map { key =>
        LibrarySlackIntegrationInfo(
          teamName = teamNameByTeamId(key.slackTeamId),
          channelName = channelNameByTeamAndChannelId((key.slackTeamId, key.slackChannelId)),
          space = externalSpaceBySpace(key.space),
          toSlack = toSlackGroupedInfos.get(key),
          fromSlack = fromSlackGroupedInfos.get(key)
        )
      }.toSeq.sortBy(x => (x.teamName.value, x.channelName.value))

      libId -> integrations
    }.toMap
  }

  def getOrganizationSlackTeam(orgId: Id[Organization], viewerId: Id[User])(implicit session: RSession): Option[OrganizationSlackTeamInfo] = {
    slackTeamRepo.getByOrganizationId(orgId).map(team => OrganizationSlackTeamInfo(team.slackTeamId, team.slackTeamName, team.publicChannelsLastSyncedAt))
  }

  @StatsdTiming("SlackInfoCommander.getOrganizationSlackInfo")
  def getOrganizationSlackInfo(orgId: Id[Organization], viewerId: Id[User], max: Option[Int])(implicit session: RSession): OrganizationSlackInfo = {
    val (libIds, basicLibsById, integrationInfosByLib, slackTeam) = {
      val slackToLibs = channelToLibRepo.getIntegrationsByOrg(orgId)
      val libToSlacks = libToChannelRepo.getIntegrationsByOrg(orgId)
      val allLibIds = (slackToLibs.map(_.libraryId) ++ libToSlacks.map(_.libraryId)).distinct.sortBy(_.id * -1)
      val libIds = max match {
        case Some(cutoff) => allLibIds.take(cutoff).toSet
        case None => allLibIds.toSet
      }

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
      val slackTeamOpt = getOrganizationSlackTeam(orgId, viewerId)

      (libIds, basicLibsById, integrationInfosByLib, slackTeamOpt)
    }

    val permissionsByLib = permissionCommander.getLibrariesPermissions(libIds, Some(viewerId))
    def canViewLib(libId: Id[Library]): Boolean = permissionsByLib.getOrElse(libId, Set.empty).contains(LibraryPermission.VIEW_LIBRARY)

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
