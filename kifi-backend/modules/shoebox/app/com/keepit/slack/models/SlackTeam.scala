package com.keepit.slack.models

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ ModelWithState, Id, State, States }
import com.keepit.common.logging.AccessLog
import com.keepit.common.core.mapExtensionOps
import com.keepit.common.time._
import com.keepit.model.{ User, Organization }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.concurrent.duration.Duration

case class SlackTeam(
  id: Option[Id[SlackTeam]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackTeam] = SlackTeamStates.ACTIVE,
  slackTeamId: SlackTeamId,
  slackTeamName: SlackTeamName,
  organizationId: Option[Id[Organization]],
  lastChannelCreatedAt: Option[SlackTimestamp] = None,
  generalChannelId: Option[SlackChannelId],
  lastDigestNotificationAt: Option[DateTime] = None,
  publicChannelsLastSyncedAt: Option[DateTime] = None,
  channelsSynced: Set[SlackChannelId] = Set.empty)
    extends ModelWithState[SlackTeam] {
  def withId(id: Id[SlackTeam]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackTeamStates.ACTIVE
  def withName(name: SlackTeamName) = this.copy(slackTeamName = name)
  def withGeneralChannelId(channelId: SlackChannelId) = this.copy(generalChannelId = Some(channelId))
  def withLastDigestNotificationAt(time: DateTime) = this.copy(lastDigestNotificationAt = Some(time))
  def withPublicChannelsSyncedAt(time: DateTime) = publicChannelsLastSyncedAt match {
    case Some(lastSync) if lastSync isAfter time => this
    case _ => this.copy(publicChannelsLastSyncedAt = Some(time))
  }
  def withSyncedChannels(newChannels: Set[SlackChannelId]) = this.copy(channelsSynced = channelsSynced ++ newChannels)

  def withOrganizationId(newOrgId: Option[Id[Organization]]): SlackTeam = organizationId match {
    case Some(orgId) if newOrgId.contains(orgId) => this
    case _ => this.copy(organizationId = newOrgId, lastChannelCreatedAt = None, lastDigestNotificationAt = None, publicChannelsLastSyncedAt = None, channelsSynced = Set.empty)
  }

  def toInternalSlackTeamInfo = InternalSlackTeamInfo(this.organizationId, this.slackTeamName)

  def unnotifiedSince: DateTime = lastDigestNotificationAt getOrElse createdAt
}

object SlackTeam {
  val cacheFormat: Format[SlackTeam] = (
    (__ \ 'id).formatNullable[Id[SlackTeam]] and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[SlackTeam]] and
    (__ \ 'slackTeamId).format[SlackTeamId] and
    (__ \ 'slackTeamName).format[SlackTeamName] and
    (__ \ 'organizationId).formatNullable[Id[Organization]] and
    (__ \ 'lastChannelCreatedAt).formatNullable[SlackTimestamp] and
    (__ \ 'generalChannelId).formatNullable[SlackChannelId] and
    (__ \ 'lastDigestNotificationAt).formatNullable[DateTime] and
    (__ \ 'publicChannelsLastSyncedAt).formatNullable[DateTime] and
    (__ \ 'channelsSynced).format[Set[SlackChannelId]]
  )(SlackTeam.apply, unlift(SlackTeam.unapply))
}

object SlackTeamStates extends States[SlackTeam]

@ImplementedBy(classOf[SlackTeamRepoImpl])
trait SlackTeamRepo extends Repo[SlackTeam] {
  def getByIds(ids: Set[Id[SlackTeam]])(implicit session: RSession): Map[Id[SlackTeam], SlackTeam]
  def getByOrganizationId(orgId: Id[Organization])(implicit session: RSession): Option[SlackTeam]
  def getByOrganizationIds(orgIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], Option[SlackTeam]]
  def getBySlackTeamId(slackTeamId: SlackTeamId, excludeState: Option[State[SlackTeam]] = Some(SlackTeamStates.INACTIVE))(implicit session: RSession): Option[SlackTeam]
  def getBySlackTeamIds(slackTeamIds: Set[SlackTeamId], excludeState: Option[State[SlackTeam]] = Some(SlackTeamStates.INACTIVE))(implicit session: RSession): Map[SlackTeamId, SlackTeam]
  def internSlackTeam(teamId: SlackTeamId, teamName: SlackTeamName)(implicit session: RWSession): SlackTeam

  def getRipeForPushingDigestNotification(lastPushOlderThan: DateTime)(implicit session: RSession): Seq[Id[SlackTeam]]
}

@Singleton
class SlackTeamRepoImpl @Inject() (
    slackTeamIdCache: SlackTeamIdCache,
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[SlackTeam] with SlackTeamRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackTeamNameColumnType = SlackDbColumnTypes.teamName(db)
  implicit val slackTimestampColumnType = SlackDbColumnTypes.timestamp(db)
  implicit val slackChannelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val slackChannelIdSetColumnType = SlackDbColumnTypes.channelIdSet(db)

  private def teamFromDbRow(id: Option[Id[SlackTeam]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[SlackTeam] = SlackTeamStates.ACTIVE,
    slackTeamId: SlackTeamId,
    slackTeamName: SlackTeamName,
    organizationId: Option[Id[Organization]],
    lastChannelCreatedAt: Option[SlackTimestamp],
    generalChannelId: Option[SlackChannelId],
    lastDigestNotificationAt: Option[DateTime],
    publicChannelsLastSyncedAt: Option[DateTime],
    channelsSynced: Set[SlackChannelId]) = {
    SlackTeam(
      id,
      createdAt,
      updatedAt,
      state,
      slackTeamId,
      slackTeamName,
      organizationId,
      lastChannelCreatedAt,
      generalChannelId,
      lastDigestNotificationAt,
      publicChannelsLastSyncedAt,
      channelsSynced
    )
  }

  private def teamToDbRow(slackTeam: SlackTeam) = Some((
    slackTeam.id,
    slackTeam.createdAt,
    slackTeam.updatedAt,
    slackTeam.state,
    slackTeam.slackTeamId,
    slackTeam.slackTeamName,
    slackTeam.organizationId,
    slackTeam.lastChannelCreatedAt,
    slackTeam.generalChannelId,
    slackTeam.lastDigestNotificationAt,
    slackTeam.publicChannelsLastSyncedAt,
    slackTeam.channelsSynced
  ))

  type RepoImpl = SlackTeamTable

  class SlackTeamTable(tag: Tag) extends RepoTable[SlackTeam](db, tag, "slack_team") {
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackTeamName = column[SlackTeamName]("slack_team_name", O.NotNull)
    def organizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)
    def lastChannelCreatedAt = column[Option[SlackTimestamp]]("last_channel_created_at", O.Nullable)
    def generalChannelId = column[Option[SlackChannelId]]("general_channel_id", O.Nullable)
    def lastDigestNotificationAt = column[Option[DateTime]]("last_digest_notification_at", O.Nullable)
    def publicChannelsLastSyncedAt = column[Option[DateTime]]("public_channels_last_synced_at", O.Nullable)
    def channelsSynced = column[Set[SlackChannelId]]("channels_synced", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, slackTeamId, slackTeamName, organizationId, lastChannelCreatedAt, generalChannelId, lastDigestNotificationAt, publicChannelsLastSyncedAt, channelsSynced) <> ((teamFromDbRow _).tupled, teamToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackTeamStates.ACTIVE)
  def table(tag: Tag) = new SlackTeamTable(tag)
  initTable()
  override def deleteCache(model: SlackTeam)(implicit session: RSession): Unit = {
    slackTeamIdCache.remove(SlackTeamIdKey(model.slackTeamId))
  }
  override def invalidateCache(model: SlackTeam)(implicit session: RSession): Unit = {
    slackTeamIdCache.set(SlackTeamIdKey(model.slackTeamId), model)
  }

  def getByIds(ids: Set[Id[SlackTeam]])(implicit session: RSession): Map[Id[SlackTeam], SlackTeam] = {
    activeRows.filter(_.id.inSet(ids)).list.map { model => model.id.get -> model }.toMap
  }

  def getByOrganizationId(orgId: Id[Organization])(implicit session: RSession): Option[SlackTeam] = {
    getByOrganizationIds(Set(orgId)).apply(orgId)
  }

  def getByOrganizationIds(orgIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], Option[SlackTeam]] = {
    val existing = activeRows.filter(row => row.organizationId.inSet(orgIds)).list
    orgIds.map(orgId => orgId -> existing.find(_.organizationId.contains(orgId))).toMap
  }

  def getBySlackTeamId(slackTeamId: SlackTeamId, excludeState: Option[State[SlackTeam]] = Some(SlackTeamStates.INACTIVE))(implicit session: RSession): Option[SlackTeam] = {
    getBySlackTeamIds(Set(slackTeamId), excludeState).get(slackTeamId)
  }

  def getBySlackTeamIds(slackTeamIds: Set[SlackTeamId], excludeState: Option[State[SlackTeam]] = Some(SlackTeamStates.INACTIVE))(implicit session: RSession): Map[SlackTeamId, SlackTeam] = {
    slackTeamIdCache.bulkGetOrElse(slackTeamIds.map(SlackTeamIdKey(_))) { missingKeys =>
      rows.filter(row => row.slackTeamId.inSet(missingKeys.map(_.id)) && row.state =!= excludeState.orNull).list.map { team => SlackTeamIdKey(team.slackTeamId) -> team }.toMap
    }.map { case (SlackTeamIdKey(slackTeamId), team) => slackTeamId -> team }
  }

  def internSlackTeam(teamId: SlackTeamId, teamName: SlackTeamName)(implicit session: RWSession): SlackTeam = {
    getBySlackTeamId(teamId, excludeState = None) match {
      case Some(team) if team.isActive =>
        val updatedTeam = team.withName(teamName)
        if (team == updatedTeam) team else save(updatedTeam)
      case inactiveTeamOpt =>
        val newTeam = SlackTeam(id = inactiveTeamOpt.flatMap(_.id), slackTeamId = teamId, slackTeamName = teamName, organizationId = None, generalChannelId = None)
        save(newTeam)
    }
  }
  def getRipeForPushingDigestNotification(lastPushOlderThan: DateTime)(implicit session: RSession): Seq[Id[SlackTeam]] = {
    activeRows.filter(row => row.lastDigestNotificationAt.isEmpty || row.lastDigestNotificationAt < lastPushOlderThan).map(_.id).list
  }

}

case class SlackTeamIdKey(id: SlackTeamId) extends Key[SlackTeam] {
  override val version = 9
  val namespace = "slack_team_by_slack_team_id"
  def toKey(): String = id.value
}
class SlackTeamIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SlackTeamIdKey, SlackTeam](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(SlackTeam.cacheFormat)

