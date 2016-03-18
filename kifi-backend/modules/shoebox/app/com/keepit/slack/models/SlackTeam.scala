package com.keepit.slack.models

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.model.{ SlackTeamIdOrgIdKey, SlackTeamIdOrgIdCache, Organization }
import org.joda.time.{ DateTime, Duration }
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.{ Duration => ConcurrentDuration }

case class SlackTeam(
  id: Option[Id[SlackTeam]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackTeam] = SlackTeamStates.ACTIVE,
  slackTeamId: SlackTeamId,
  slackTeamName: SlackTeamName,
  organizationId: Option[Id[Organization]],
  generalChannelId: Option[SlackChannelId],
  lastDigestNotificationAt: Option[DateTime] = None,
  publicChannelsLastSyncedAt: Option[DateTime] = None,
  channelsLastSyncingAt: Option[DateTime] = None,
  channelsSynced: Set[SlackChannelId] = Set.empty,
  kifiBot: Option[KifiSlackBot],
  noPersonalDigestsUntil: DateTime = currentDateTime)
    extends ModelWithState[SlackTeam] {
  def withId(id: Id[SlackTeam]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackTeamStates.ACTIVE
  def withName(name: SlackTeamName) = this.copy(slackTeamName = name)
  def withGeneralChannelId(channelId: SlackChannelId) = this.copy(generalChannelId = Some(channelId))
  def withLastDigestNotificationAt(time: DateTime) = this.copy(lastDigestNotificationAt = Some(time))
  def withNoPersonalDigestsUntil(time: DateTime) = this.copy(noPersonalDigestsUntil = time)
  def withPublicChannelsSyncedAt(time: DateTime) = publicChannelsLastSyncedAt match {
    case Some(lastSync) if lastSync isAfter time => this
    case _ => this.copy(publicChannelsLastSyncedAt = Some(time))
  }

  def doneSyncing = this.copy(channelsLastSyncingAt = None)
  def withSyncedChannels(newChannels: Set[SlackChannelId]) = this.copy(channelsSynced = channelsSynced ++ newChannels)

  def withNoKifiBot = this.copy(kifiBot = None)
  def withKifiBotIfDefined(kifiBotOpt: Option[KifiSlackBot]) = kifiBotOpt match {
    case Some(newBot) => this.copy(kifiBot = Some(newBot))
    case None => this
  }

  def withOrganizationId(newOrgId: Option[Id[Organization]]): SlackTeam = organizationId match {
    case Some(orgId) if newOrgId.contains(orgId) => this
    case _ => this.copy(organizationId = newOrgId, lastDigestNotificationAt = None, channelsLastSyncingAt = None, publicChannelsLastSyncedAt = None, channelsSynced = Set.empty)
  }

  def toInternalSlackTeamInfo = InternalSlackTeamInfo(this.organizationId, this.slackTeamName)

  def unnotifiedSince: DateTime = lastDigestNotificationAt getOrElse createdAt

  def getKifiBotTokenIncludingScopes(requiredScopes: Set[SlackAuthScope]): Option[SlackBotAccessToken] =
    if (requiredScopes subsetOf SlackAuthScope.inheritableBotScopes) kifiBot.map(_.token) else None
}
case class KifiSlackBot(userId: SlackUserId, token: SlackBotAccessToken)
object KifiSlackBot {
  implicit val format = Json.format[KifiSlackBot]
  def fromAuth(botAuth: SlackBotUserAuthorization) = KifiSlackBot(botAuth.userId, botAuth.accessToken)
}
object SlackTeamStates extends States[SlackTeam]

object SlackTeam {
  val cacheFormat: Format[SlackTeam] = (
    (__ \ 'id).formatNullable[Id[SlackTeam]] and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[SlackTeam]] and
    (__ \ 'slackTeamId).format[SlackTeamId] and
    (__ \ 'slackTeamName).format[SlackTeamName] and
    (__ \ 'organizationId).formatNullable[Id[Organization]] and
    (__ \ 'generalChannelId).formatNullable[SlackChannelId] and
    (__ \ 'lastDigestNotificationAt).formatNullable[DateTime] and
    (__ \ 'publicChannelsLastSyncingAt).formatNullable[DateTime] and
    (__ \ 'publicChannelsLastSyncedAt).formatNullable[DateTime] and
    (__ \ 'channelsSynced).format[Set[SlackChannelId]] and
    (__ \ 'kifiBot).formatNullable[KifiSlackBot] and
    (__ \ 'noPersonalDigestsUntil).format[DateTime]
  )(SlackTeam.apply, unlift(SlackTeam.unapply))
}

@ImplementedBy(classOf[SlackTeamRepoImpl])
trait SlackTeamRepo extends Repo[SlackTeam] {
  def getByIds(ids: Set[Id[SlackTeam]])(implicit session: RSession): Map[Id[SlackTeam], SlackTeam]
  def getByOrganizationId(orgId: Id[Organization])(implicit session: RSession): Option[SlackTeam]
  def getByOrganizationIds(orgIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], Option[SlackTeam]]
  def getSlackTeamIds(orgIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], SlackTeamId]
  def getBySlackTeamIdNoCache(slackTeamId: SlackTeamId, excludeState: Option[State[SlackTeam]] = Some(SlackTeamStates.INACTIVE))(implicit session: RSession): Option[SlackTeam]
  def getBySlackTeamId(slackTeamId: SlackTeamId, excludeState: Option[State[SlackTeam]] = Some(SlackTeamStates.INACTIVE))(implicit session: RSession): Option[SlackTeam]
  def getBySlackTeamIds(slackTeamIds: Set[SlackTeamId])(implicit session: RSession): Map[SlackTeamId, SlackTeam]
  def getByKifiBotToken(token: SlackBotAccessToken)(implicit session: RSession): Option[SlackTeam]
  def internSlackTeam(teamId: SlackTeamId, teamName: SlackTeamName, botAuth: Option[SlackBotUserAuthorization])(implicit session: RWSession): SlackTeam

  def getRipeForPushingDigestNotification(lastPushOlderThan: DateTime)(implicit session: RSession): Seq[Id[SlackTeam]]
  def markAsSyncingChannels(slackTeamId: SlackTeamId, syncTimeout: Duration)(implicit session: RWSession): Boolean

  //admin
  def getAllActiveWithOrgAndWithoutKifiBotToken()(implicit session: RSession): Seq[SlackTeam]
}

@Singleton
class SlackTeamRepoImpl @Inject() (
    slackTeamIdByOrganizationIdCache: SlackTeamIdOrgIdCache,
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[SlackTeam] with SlackTeamRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackTeamNameColumnType = SlackDbColumnTypes.teamName(db)
  implicit val slackTimestampColumnType = SlackDbColumnTypes.timestamp(db)
  implicit val slackChannelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val slackChannelIdSetColumnType = SlackDbColumnTypes.channelIdSet(db)
  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val tokenColumnType = MappedColumnType.base[SlackBotAccessToken, String](_.token, SlackBotAccessToken(_))

  private def teamFromDbRow(id: Option[Id[SlackTeam]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[SlackTeam] = SlackTeamStates.ACTIVE,
    slackTeamId: SlackTeamId,
    slackTeamName: SlackTeamName,
    organizationId: Option[Id[Organization]],
    generalChannelId: Option[SlackChannelId],
    lastDigestNotificationAt: Option[DateTime],
    publicChannelsLastSyncedAt: Option[DateTime],
    channelsLastSyncingAt: Option[DateTime],
    channelsSynced: Set[SlackChannelId],
    kifiBotUserId: Option[SlackUserId],
    kifiBotToken: Option[SlackBotAccessToken],
    noPersonalDigestsUntil: DateTime) = {
    SlackTeam(
      id,
      createdAt,
      updatedAt,
      state,
      slackTeamId,
      slackTeamName,
      organizationId,
      generalChannelId,
      lastDigestNotificationAt,
      publicChannelsLastSyncedAt,
      channelsLastSyncingAt,
      channelsSynced,
      for { botId <- kifiBotUserId; botToken <- kifiBotToken } yield KifiSlackBot(botId, botToken),
      noPersonalDigestsUntil
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
    slackTeam.generalChannelId,
    slackTeam.lastDigestNotificationAt,
    slackTeam.publicChannelsLastSyncedAt,
    slackTeam.channelsLastSyncingAt,
    slackTeam.channelsSynced,
    slackTeam.kifiBot.map(_.userId),
    slackTeam.kifiBot.map(_.token),
    slackTeam.noPersonalDigestsUntil
  ))

  type RepoImpl = SlackTeamTable

  class SlackTeamTable(tag: Tag) extends RepoTable[SlackTeam](db, tag, "slack_team") {
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackTeamName = column[SlackTeamName]("slack_team_name", O.NotNull)
    def organizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)
    def generalChannelId = column[Option[SlackChannelId]]("general_channel_id", O.Nullable)
    def lastDigestNotificationAt = column[Option[DateTime]]("last_digest_notification_at", O.Nullable)
    def publicChannelsLastSyncedAt = column[Option[DateTime]]("public_channels_last_synced_at", O.Nullable)
    def channelsLastSyncingAt = column[Option[DateTime]]("channels_last_syncing_at", O.Nullable)
    def channelsSynced = column[Set[SlackChannelId]]("channels_synced", O.NotNull)
    def kifiBotUserId = column[Option[SlackUserId]]("kifi_bot_user_id", O.Nullable)
    def kifiBotToken = column[Option[SlackBotAccessToken]]("kifi_bot_token", O.Nullable)
    def noPersonalDigestsUntil = column[DateTime]("no_personal_digests_until", O.NotNull)
    def * = (
      id.?, createdAt, updatedAt, state, slackTeamId, slackTeamName, organizationId,
      generalChannelId, lastDigestNotificationAt, publicChannelsLastSyncedAt, channelsLastSyncingAt, channelsSynced,
      kifiBotUserId, kifiBotToken, noPersonalDigestsUntil
    ) <> ((teamFromDbRow _).tupled, teamToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackTeamStates.ACTIVE)
  def table(tag: Tag) = new SlackTeamTable(tag)
  initTable()

  override def save(model: SlackTeam)(implicit session: RWSession): SlackTeam = {
    model.id.foreach(id => deleteCache(get(id))) // proper cache invalidation requires fields from the old model
    super.save(model)
  }

  override def deleteCache(model: SlackTeam)(implicit session: RSession): Unit = {
    model.organizationId.foreach(orgId => slackTeamIdByOrganizationIdCache.remove(SlackTeamIdOrgIdKey(orgId)))
  }
  override def invalidateCache(model: SlackTeam)(implicit session: RSession): Unit = {
    deleteCache(model)
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

  def getSlackTeamIds(orgIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], SlackTeamId] = {
    slackTeamIdByOrganizationIdCache.bulkGetOrElseOpt(orgIds.map(SlackTeamIdOrgIdKey(_))) { missingKeys =>
      getByOrganizationIds(missingKeys.map(_.organizationId)).map { case (orgId, slackTeamOpt) => SlackTeamIdOrgIdKey(orgId) -> slackTeamOpt.map(_.slackTeamId) }
    }.collect { case (SlackTeamIdOrgIdKey(orgId), Some(slackTeamId)) => orgId -> slackTeamId }
  }

  def getBySlackTeamIdNoCache(slackTeamId: SlackTeamId, excludeState: Option[State[SlackTeam]] = Some(SlackTeamStates.INACTIVE))(implicit session: RSession): Option[SlackTeam] = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.state =!= excludeState.orNull).firstOption
  }

  def getBySlackTeamId(slackTeamId: SlackTeamId, excludeState: Option[State[SlackTeam]] = Some(SlackTeamStates.INACTIVE))(implicit session: RSession): Option[SlackTeam] = {
    rows.filter(r => r.slackTeamId === slackTeamId && r.state =!= excludeState.orNull).firstOption
  }

  def getBySlackTeamIds(slackTeamIds: Set[SlackTeamId])(implicit session: RSession): Map[SlackTeamId, SlackTeam] = {
    activeRows.filter(row => row.slackTeamId.inSet(slackTeamIds)).list.map(st => st.slackTeamId -> st).toMap
  }

  def getByKifiBotToken(token: SlackBotAccessToken)(implicit session: RSession): Option[SlackTeam] = {
    activeRows.filter(_.kifiBotToken === token).firstOption
  }

  def internSlackTeam(teamId: SlackTeamId, teamName: SlackTeamName, botAuth: Option[SlackBotUserAuthorization])(implicit session: RWSession): SlackTeam = {
    getBySlackTeamId(teamId, excludeState = None) match {
      case Some(team) if team.isActive =>
        val updatedTeam = team.withName(teamName).withKifiBotIfDefined(botAuth.map(KifiSlackBot.fromAuth))
        if (team == updatedTeam) team else save(updatedTeam)
      case inactiveTeamOpt =>
        val newTeam = SlackTeam(
          id = inactiveTeamOpt.flatMap(_.id),
          slackTeamId = teamId,
          slackTeamName = teamName,
          organizationId = None,
          generalChannelId = None,
          kifiBot = botAuth.map(KifiSlackBot.fromAuth)
        )
        save(newTeam)
    }
  }
  def getRipeForPushingDigestNotification(lastPushOlderThan: DateTime)(implicit session: RSession): Seq[Id[SlackTeam]] = {
    activeRows.filter(row => row.lastDigestNotificationAt.isEmpty || row.lastDigestNotificationAt < lastPushOlderThan).map(_.id).list
  }

  def markAsSyncingChannels(slackTeamId: SlackTeamId, syncTimeout: Duration)(implicit session: RWSession): Boolean = {
    val now = clock.now()
    val syncTimeoutAt = now minus syncTimeout
    rows.filter(r => r.slackTeamId === slackTeamId && (r.channelsLastSyncingAt.isEmpty || r.channelsLastSyncingAt <= syncTimeoutAt)).map(r => (r.updatedAt, r.channelsLastSyncingAt)).update((now, Some(now))) > 0
  }

  def getAllActiveWithOrgAndWithoutKifiBotToken()(implicit session: RSession): Seq[SlackTeam] = activeRows.filter(r => r.kifiBotToken.isEmpty && r.organizationId.isDefined).list

}

