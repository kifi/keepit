package com.keepit.slack

import com.google.inject.{ Provider, ImplementedBy, Inject, Singleton }
import com.keepit.commanders.gen.BasicOrganizationGen
import com.keepit.commanders.{ OrganizationInfoCommander, PermissionCommander }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.KeyFormat
import com.keepit.common.logging.Logging
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.social.BasicUserRepo
import com.keepit.model.ExternalLibrarySpace.ExternalOrganizationSpace
import com.keepit.model._
import com.keepit.slack.models._
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json.{ Json, Writes }
import com.keepit.common.core._

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
case class FullLibrarySlackInfo( // everything needed for modifying integrations on libraries
  link: String,
  integrations: Seq[LibrarySlackIntegrationInfo])

@json
case class OrganizationSlackTeamInfo(id: SlackTeamId, name: SlackTeamName, publicChannelsLastSyncedAt: Option[DateTime])

case class OrganizationSlackInfo(
  slackTeam: Option[OrganizationSlackTeamInfo],
  libraries: Seq[(BasicLibrary, FullLibrarySlackInfo)])

object OrganizationSlackInfo {
  private implicit val helperWrites = KeyFormat.key2Writes[BasicLibrary, FullLibrarySlackInfo]("library", "slack")
  implicit val writes: Writes[OrganizationSlackInfo] = Json.writes[OrganizationSlackInfo]
}

case class UserSlackInfo(memberships: Seq[UserSlackTeamInfo])

@json
case class UserSlackTeamInfo(
  teamId: SlackTeamId,
  orgId: Option[PublicId[Organization]],
  teamName: SlackTeamName,
  slackUserId: SlackUserId,
  username: Option[SlackUsername],
  privateChannelsLastSyncedAt: Option[DateTime],
  personalDigestSetting: SlackPersonalDigestSetting)

object UserSlackInfo {
  def empty = UserSlackInfo(memberships = Seq.empty)
  implicit val writes: Writes[UserSlackInfo] = Json.writes[UserSlackInfo]
}

@ImplementedBy(classOf[SlackInfoCommanderImpl])
trait SlackInfoCommander {
  // For use in the LibraryInfoCommander to send info down to clients
  def getFullSlackInfoForLibraries(userId: Id[User], libraryIds: Set[Id[Library]]): Map[Id[Library], FullLibrarySlackInfo]
  def getLiteSlackInfoForLibraries(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], LiteLibrarySlackInfo]

  // For generating OrganizationInfo
  def getOrganizationSlackInfo(orgId: Id[Organization], viewerId: Id[User], max: Option[Int] = None)(implicit session: RSession): OrganizationSlackInfo
  def getOrganizationSlackTeam(orgId: Id[Organization])(implicit session: RSession): Option[OrganizationSlackTeamInfo]

  // For generating UserProfileStats
  def getUserSlackInfo(userId: Id[User], viewerId: Option[Id[User]])(implicit session: RSession): UserSlackInfo

  def getOrganizationSlackTeamsForUser(userId: Id[User])(implicit session: RSession): Set[SlackTeamId]
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
  basicOrganizationGen: BasicOrganizationGen,
  permissionCommander: PermissionCommander,
  airbrake: AirbrakeNotifier,
  liteSlackInfoCache: LiteLibrarySlackInfoCache,
  implicit val publicIdConfiguration: PublicIdConfiguration)
    extends SlackInfoCommander with Logging {

  @StatsdTiming("SlackInfoCommander.getSlackIntegrationsForLibraries")
  def getFullSlackInfoForLibraries(viewerId: Id[User], libraryIds: Set[Id[Library]]): Map[Id[Library], FullLibrarySlackInfo] = {
    val integrationInfosByLib = db.readOnlyMaster { implicit session =>
      val slackTeamIds = getOrganizationSlackTeamsForUser(viewerId)
      val slackToLibs = channelToLibRepo.getAllBySlackTeamsAndLibraries(slackTeamIds, libraryIds)
      val libToSlacks = libToChannelRepo.getAllBySlackTeamsAndLibraries(slackTeamIds, libraryIds)
      generateLibrarySlackIntegrationInfos(viewerId, slackToLibs, libToSlacks)
    }
    assembleFullLibrarySlackInfos(libraryIds, integrationInfosByLib)
  }

  def getLiteSlackInfoForLibraries(libraries: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], LiteLibrarySlackInfo] = {
    liteSlackInfoCache.bulkGetOrElse(libraries.map(LiteLibrarySlackInfoKey)) { missingKeys =>
      generateLiteLibrarySlackInfos(missingKeys.map(_.libraryId)).map { case (libId, info) => LiteLibrarySlackInfoKey(libId) -> info }
    }.map { case (LiteLibrarySlackInfoKey(libId), libSlackInfo) => libId -> libSlackInfo }
  }

  private def generateLiteLibrarySlackInfos(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], LiteLibrarySlackInfo] = {
    val ltscsByLib = libToChannelRepo.getActiveByLibraries(libraryIds)
    val channelsById = slackChannelRepo.getByChannelIds(ltscsByLib.values.flatten.map(ltsc => (ltsc.slackTeamId, ltsc.slackChannelId)).toSet)
    ltscsByLib.map {
      case (libraryId, ltscs) =>
        val basicChannels = ltscs.map { ltsc =>
          val channel = channelsById((ltsc.slackTeamId, ltsc.slackChannelId))
          BasicSlackChannel(channel.prettyName.getOrElse(channel.slackChannelName))
        }
        libraryId -> LiteLibrarySlackInfo(basicChannels.toSeq)
    }
  }

  private def assembleFullLibrarySlackInfos(libraryIds: Set[Id[Library]], integrationInfosByLib: Map[Id[Library], Seq[LibrarySlackIntegrationInfo]]): Map[Id[Library], FullLibrarySlackInfo] = {
    libraryIds.map { libId =>
      val pubLibId = Library.publicId(libId)
      libId -> FullLibrarySlackInfo(
        link = "whatever", //com.keepit.controllers.website.routes.SlackController.setupLibraryIntegrations(pubLibId).url,
        integrations = integrationInfosByLib.getOrElse(libId, Seq.empty)
      )
    }.toMap
  }

  private def generateLibrarySlackIntegrationInfos(viewerId: Id[User], slackToLibs: Seq[SlackChannelToLibrary], libToSlacks: Seq[LibraryToSlackChannel])(implicit session: RSession): Map[Id[Library], Seq[LibrarySlackIntegrationInfo]] = {
    val libraryIds = (slackToLibs.map(_.libraryId) ++ libToSlacks.map(_.libraryId)).toSet
    val permissionsByLib = permissionCommander.getLibrariesPermissions(libraryIds, Some(viewerId))
    val canUserJoinOrWritetoLib = permissionCommander.canJoinOrWriteToLibrary(viewerId, libraryIds)

    val slackTeamByTeamId = slackTeamRepo.getBySlackTeamIds((slackToLibs.map(_.slackTeamId) ++ libToSlacks.map(_.slackTeamId)).toSet)
    val teamNameByTeamId = slackTeamByTeamId.mapValues(_.slackTeamName)
    val externalSpaceByTeamId = slackTeamByTeamId.mapValues(_.organizationId).collect { case (teamId, Some(orgId)) => (teamId, ExternalOrganizationSpace(Organization.publicId(orgId))) }

    val channelNameByTeamAndChannelId = {
      val channelIds = slackToLibs.map(stl => (stl.slackTeamId, stl.slackChannelId)).toSet ++ libToSlacks.map(lts => (lts.slackTeamId, lts.slackChannelId))
      slackChannelRepo.getByChannelIds(channelIds).mapValues(channel => channel.prettyName getOrElse channel.slackChannelName)
    }

    case class SlackIntegrationInfoKey(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId)
    object SlackIntegrationInfoKey {
      def fromSTL(stl: SlackChannelToLibrary) = SlackIntegrationInfoKey(stl.slackTeamId, stl.slackChannelId)
      def fromLTS(lts: LibraryToSlackChannel) = SlackIntegrationInfoKey(lts.slackTeamId, lts.slackChannelId)
    }
    libraryIds.map { libId =>
      val permissions = permissionsByLib.getOrElse(libId, Set.empty)
      val fromSlacksThisLib = slackToLibs.filter(_.libraryId == libId)
      val toSlacksThisLib = libToSlacks.filter(_.libraryId == libId)

      val fromSlackGroupedInfos = fromSlacksThisLib.groupBy(SlackIntegrationInfoKey.fromSTL).map {
        case (key, fs +: thisSeqReallyOughtToBeEmpty) =>
          airbrake.verify(thisSeqReallyOughtToBeEmpty.isEmpty, s"Uniqueness constraint violation on slack_channel_to_library $libId + $key")
          val fsPubId = SlackChannelToLibrary.publicId(fs.id.get)
          key -> SlackToLibraryIntegrationInfo(fsPubId, fs.status, isMutable = canUserJoinOrWritetoLib.getOrElse(libId, false))
      }
      val toSlackGroupedInfos = toSlacksThisLib.groupBy(SlackIntegrationInfoKey.fromLTS).map {
        case (key, ts +: thisSeqReallyOughtToBeEmpty) =>
          airbrake.verify(thisSeqReallyOughtToBeEmpty.isEmpty, s"Uniqueness constraint violation on library_to_slack_channel $libId + $key")
          val tsPubId = LibraryToSlackChannel.publicId(ts.id.get)
          key -> LibraryToSlackIntegrationInfo(tsPubId, ts.status, isMutable = permissions.contains(LibraryPermission.VIEW_LIBRARY))
      }
      val integrations = (fromSlackGroupedInfos.keySet ++ toSlackGroupedInfos.keySet).map { key =>
        LibrarySlackIntegrationInfo(
          teamName = teamNameByTeamId(key.slackTeamId),
          channelName = channelNameByTeamAndChannelId((key.slackTeamId, key.slackChannelId)),
          space = externalSpaceByTeamId(key.slackTeamId),
          toSlack = toSlackGroupedInfos.get(key),
          fromSlack = fromSlackGroupedInfos.get(key)
        )
      }.toSeq.sortBy(x => (x.teamName.value, x.channelName.value))

      libId -> integrations
    }.toMap
  }

  def getOrganizationSlackTeam(orgId: Id[Organization])(implicit session: RSession): Option[OrganizationSlackTeamInfo] = {
    slackTeamRepo.getByOrganizationId(orgId).map(team => OrganizationSlackTeamInfo(team.slackTeamId, team.slackTeamName, team.publicChannelsLastSyncedAt))
  }

  @StatsdTiming("SlackInfoCommander.getOrganizationSlackInfo")
  def getOrganizationSlackInfo(orgId: Id[Organization], viewerId: Id[User], max: Option[Int])(implicit session: RSession): OrganizationSlackInfo = {
    val (libIds, basicLibsById, integrationInfosByLib, slackTeam) = {
      val slackTeamOpt = getOrganizationSlackTeam(orgId)
      val slackTeamIdOpt = slackTeamOpt.map(_.id)
      val (visibleLibraryIds, visibleSlackToLibs, visibleLibToSlacks) = {
        val allSlackToLibs = slackTeamIdOpt.map(channelToLibRepo.getBySlackTeam(_)) getOrElse Seq.empty
        val allLibToSlacks = slackTeamIdOpt.map(libToChannelRepo.getBySlackTeam(_)) getOrElse Seq.empty
        val allLibraryIds = (allSlackToLibs.map(_.libraryId) ++ allLibToSlacks.map(_.libraryId)).toSet
        val permissionsByLibraryId = permissionCommander.getLibrariesPermissions(allLibraryIds, Some(viewerId))
        val visibleLibraryIds = allLibraryIds.filter(libraryId => permissionsByLibraryId.get(libraryId).exists(_.contains(LibraryPermission.VIEW_LIBRARY))).toSeq.sortBy(_.id * -1)
        val visibleSlackToLibs = allSlackToLibs.filter(stl => visibleLibraryIds.contains(stl.libraryId))
        val visibleLibToSlacks = allLibToSlacks.filter(lts => visibleLibraryIds.contains(lts.libraryId))
        (visibleLibraryIds, visibleSlackToLibs, visibleLibToSlacks)
      }

      val libIds = max match {
        case Some(cutoff) => visibleLibraryIds.take(cutoff).toSet
        case None => visibleLibraryIds.toSet
      }

      val slackInfoByLibId = getLiteSlackInfoForLibraries(libIds)

      val basicLibsById = {
        val libs = libRepo.getActiveByIds(libIds).values.toList
        val owners = libs.map(_.ownerId).toSet
        val basicUserById = basicUserRepo.loadAll(owners)
        val orgIds = libs.flatMap(_.organizationId).toSet
        val basicOrgs = orgIds.flatMap { orgId => basicOrganizationGen.getBasicOrganizationHelper(orgId).map(orgId -> _) }.toMap
        libs.map { lib =>
          lib.id.get -> BasicLibrary(lib, basicUserById(lib.ownerId).username, lib.organizationId.flatMap(basicOrgs.get).map(_.handle), slackInfoByLibId.get(lib.id.get))
        }.toMap
      }
      val integrationInfosByLib = generateLibrarySlackIntegrationInfos(viewerId, visibleSlackToLibs, visibleLibToSlacks)

      (libIds, basicLibsById, integrationInfosByLib, slackTeamOpt)
    }

    val permissionsByLib = permissionCommander.getLibrariesPermissions(libIds, Some(viewerId))
    def canViewLib(libId: Id[Library]): Boolean = permissionsByLib.getOrElse(libId, Set.empty).contains(LibraryPermission.VIEW_LIBRARY)

    val librarySlackInfosByLib = assembleFullLibrarySlackInfos(libIds, integrationInfosByLib)
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
      val teamsById = slackTeamRepo.getBySlackTeamIds(memberships.map(_.slackTeamId).toSet)
      val userSlackTeamInfos = memberships.map { stm =>
        val orgIdOpt = slackTeamRepo.getBySlackTeamId(stm.slackTeamId).flatMap(_.organizationId)
        val slackTeamName = teamsById(stm.slackTeamId).slackTeamName
        UserSlackTeamInfo(stm.slackTeamId, orgIdOpt.map(Organization.publicId), slackTeamName, stm.slackUserId, stm.slackUsername, stm.privateChannelsLastSyncedAt, stm.personalDigestSetting)
      }
      UserSlackInfo(userSlackTeamInfos)
    }
  }

  def getOrganizationSlackTeamsForUser(userId: Id[User])(implicit session: RSession): Set[SlackTeamId] = {
    slackTeamRepo.getSlackTeamIds(orgMembershipRepo.getAllByUserId(userId).map(_.organizationId).toSet).values.toSet
  }
}
