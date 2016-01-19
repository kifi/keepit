package com.keepit.slack.models

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ ModelWithState, Id, State, States }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.model.{ User, Organization }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.concurrent.duration.Duration

case class InvalidSlackSetupException(userId: Id[User], team: Option[SlackTeam], membership: Option[SlackTeamMembership])
  extends Exception(s"Invalid Slack setup for user $userId in team $team with membership $membership")

case class UnauthorizedSlackTeamOrganizationModificationException(team: Option[SlackTeam], userId: Id[User], newOrganizationId: Option[Id[Organization]])
  extends Exception(s"Unauthorized request from user $userId to connect ${team.map(_.toString) getOrElse "unkwown SlackTeam"} with organization $newOrganizationId.")

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
  lastDigestNotificationAt: DateTime = currentDateTime)
    extends ModelWithState[SlackTeam] {
  def withId(id: Id[SlackTeam]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackTeamStates.ACTIVE
  def withName(name: SlackTeamName) = this.copy(slackTeamName = name)
  def withGeneralChannelId(channelId: SlackChannelId) = this.copy(generalChannelId = Some(channelId))
  def withLastDigestNotificationAt(time: DateTime) = this.copy(lastDigestNotificationAt = time)

  def toInternalSlackTeamInfo = InternalSlackTeamInfo(this.organizationId, this.slackTeamName)
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
    (__ \ 'lastDigestNotificationAt).format[DateTime]
  )(SlackTeam.apply, unlift(SlackTeam.unapply))
}

object SlackTeamStates extends States[SlackTeam]

@ImplementedBy(classOf[SlackTeamRepoImpl])
trait SlackTeamRepo extends Repo[SlackTeam] {
  def getByOrganizationId(orgId: Id[Organization])(implicit session: RSession): Set[SlackTeam]
  def getBySlackTeamId(slackTeamId: SlackTeamId, excludeState: Option[State[SlackTeam]] = Some(SlackTeamStates.INACTIVE))(implicit session: RSession): Option[SlackTeam]
  def internSlackTeam(identity: SlackIdentifyResponse)(implicit session: RWSession): SlackTeam
  def getRipeForPushingDigestNotification(lastPushOlderThan: DateTime)(implicit session: RSession): Seq[SlackTeam]
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

  private def teamFromDbRow(id: Option[Id[SlackTeam]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[SlackTeam] = SlackTeamStates.ACTIVE,
    slackTeamId: SlackTeamId,
    slackTeamName: SlackTeamName,
    organizationId: Option[Id[Organization]],
    lastChannelCreatedAt: Option[SlackTimestamp],
    generalChannelId: Option[SlackChannelId],
    lastDigestNotificationAt: DateTime) = {
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
      lastDigestNotificationAt
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
    slackTeam.lastDigestNotificationAt
  ))

  type RepoImpl = SlackTeamTable

  class SlackTeamTable(tag: Tag) extends RepoTable[SlackTeam](db, tag, "slack_team") {
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackTeamName = column[SlackTeamName]("slack_team_name", O.NotNull)
    def organizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)
    def lastChannelCreatedAt = column[Option[SlackTimestamp]]("last_channel_created_at", O.Nullable)
    def generalChannelId = column[Option[SlackChannelId]]("general_channel_id", O.Nullable)
    def lastDigestNotificationAt = column[DateTime]("last_digest_notification_at", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, slackTeamId, slackTeamName, organizationId, lastChannelCreatedAt, generalChannelId, lastDigestNotificationAt) <> ((teamFromDbRow _).tupled, teamToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackTeamStates.ACTIVE)
  def table(tag: Tag) = new SlackTeamTable(tag)
  initTable()
  override def deleteCache(membership: SlackTeam)(implicit session: RSession): Unit = {
    slackTeamIdCache.remove(SlackTeamIdKey(membership.slackTeamId))
  }
  override def invalidateCache(membership: SlackTeam)(implicit session: RSession): Unit = {
    slackTeamIdCache.set(SlackTeamIdKey(membership.slackTeamId), membership)
  }

  def getByOrganizationId(orgId: Id[Organization])(implicit session: RSession): Set[SlackTeam] = {
    activeRows.filter(row => row.organizationId === orgId).list.toSet
  }

  def getBySlackTeamId(slackTeamId: SlackTeamId, excludeState: Option[State[SlackTeam]] = Some(SlackTeamStates.INACTIVE))(implicit session: RSession): Option[SlackTeam] = {
    slackTeamIdCache.getOrElseOpt(SlackTeamIdKey(slackTeamId)) {
      rows.filter(row => row.slackTeamId === slackTeamId && row.state =!= excludeState.orNull).firstOption
    }
  }

  def internSlackTeam(identity: SlackIdentifyResponse)(implicit session: RWSession): SlackTeam = {
    getBySlackTeamId(identity.teamId, excludeState = None) match {
      case Some(team) if team.isActive =>
        val updatedTeam = team.withName(identity.teamName)
        if (team == updatedTeam) team else save(updatedTeam)
      case inactiveTeamOpt =>
        val newTeam = SlackTeam(id = inactiveTeamOpt.flatMap(_.id), slackTeamId = identity.teamId, slackTeamName = identity.teamName, organizationId = None, generalChannelId = None)
        save(newTeam)
    }
  }
  def getRipeForPushingDigestNotification(lastPushOlderThan: DateTime)(implicit session: RSession): Seq[SlackTeam] = {
    activeRows.filter(row => row.lastDigestNotificationAt < lastPushOlderThan).list
  }

}

case class SlackTeamIdKey(id: SlackTeamId) extends Key[SlackTeam] {
  override val version = 1
  val namespace = "slack_team_by_slack_team_id"
  def toKey(): String = id.value
}
class SlackTeamIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SlackTeamIdKey, SlackTeam](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(SlackTeam.cacheFormat)

