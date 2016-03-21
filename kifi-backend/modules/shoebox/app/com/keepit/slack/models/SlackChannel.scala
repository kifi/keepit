package com.keepit.slack.models

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class SlackChannel(
  id: Option[Id[SlackChannel]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackChannel] = SlackChannelStates.ACTIVE,
  slackTeamId: SlackTeamId,
  slackChannelId: SlackChannelId,
  slackChannelName: SlackChannelName,
  lastNotificationAt: Option[DateTime] = None)
    extends Model[SlackChannel] with ModelWithState[SlackChannel] {

  def withId(newId: Id[SlackChannel]) = this.copy(id = Some(newId))
  def withUpdateTime(time: DateTime) = this.copy(updatedAt = time)
  def withLastNotificationAtLeast(time: DateTime) = if (lastNotificationAt.exists(_ isAfter time)) this else this.copy(lastNotificationAt = Some(time))

  def unnotifiedSince: DateTime = lastNotificationAt getOrElse createdAt

  def idAndName: (SlackChannelId, SlackChannelName) = (slackChannelId, slackChannelName)
  def prettyName: Option[SlackChannelName] = SlackChannelIdAndPrettyName.from(slackChannelId, slackChannelName).name
  def isActive: Boolean = state == SlackChannelStates.ACTIVE
}

object SlackChannelStates extends States[SlackChannel]

@ImplementedBy(classOf[SlackChannelRepoImpl])
trait SlackChannelRepo extends Repo[SlackChannel] {
  def getByIds(ids: Set[Id[SlackChannel]])(implicit session: RSession): Map[Id[SlackChannel], SlackChannel]
  def getByChannelIds(slackTeamAndChannelIds: Set[(SlackTeamId, SlackChannelId)])(implicit session: RSession): Map[(SlackTeamId, SlackChannelId), SlackChannel]
  def getByChannelId(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId)(implicit session: RSession): Option[SlackChannel]
  def getOrCreate(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, slackChannelName: SlackChannelName)(implicit session: RWSession): SlackChannel
  def getRipeForPushingDigestNotification(lastPushOlderThan: DateTime)(implicit session: RSession): Seq[Id[SlackChannel]]

  def adminGetChannel(channelId: SlackChannelId)(implicit session: RSession): Option[SlackChannel]
}

@Singleton
class SlackChannelRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[SlackChannel] with SlackChannelRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackChannelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val slackChannelNameColumnType = SlackDbColumnTypes.channelName(db)

  private def fromDbRow(id: Option[Id[SlackChannel]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[SlackChannel],
    slackTeamId: SlackTeamId,
    slackChannelId: SlackChannelId,
    slackChannelName: SlackChannelName,
    lastNotificationAt: Option[DateTime]) = {
    SlackChannel(
      id,
      createdAt,
      updatedAt,
      state,
      slackTeamId,
      slackChannelId,
      slackChannelName,
      lastNotificationAt
    )
  }

  private def toDbRow(sc: SlackChannel) = Some((
    sc.id,
    sc.createdAt,
    sc.updatedAt,
    sc.state,
    sc.slackTeamId,
    sc.slackChannelId,
    sc.slackChannelName,
    sc.lastNotificationAt
  ))

  type RepoImpl = SlackChannelTable

  class SlackChannelTable(tag: Tag) extends RepoTable[SlackChannel](db, tag, "slack_channel") {
    // There is a unique index on (slackTeamId, slackChannelId)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackChannelId = column[SlackChannelId]("slack_channel_id", O.NotNull)
    def slackChannelName = column[SlackChannelName]("slack_channel_name", O.NotNull)
    def lastNotificationAt = column[Option[DateTime]]("last_notification_at", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, slackTeamId, slackChannelId, slackChannelName, lastNotificationAt) <> ((fromDbRow _).tupled, toDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackChannelStates.ACTIVE)
  def table(tag: Tag) = new SlackChannelTable(tag)
  initTable()
  override def deleteCache(model: SlackChannel)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: SlackChannel)(implicit session: RSession): Unit = {}

  def getByIds(ids: Set[Id[SlackChannel]])(implicit session: RSession): Map[Id[SlackChannel], SlackChannel] = {
    activeRows.filter(_.id.inSet(ids)).list.map { model => model.id.get -> model }.toMap
  }

  def getByChannelIds(slackTeamAndChannelIds: Set[(SlackTeamId, SlackChannelId)])(implicit session: RSession): Map[(SlackTeamId, SlackChannelId), SlackChannel] = {
    // This query looks up channels from the db by slack channel id only (so we could get some extra values back).
    // We then pare down to the correct values in-memory
    val slackChannelIds = slackTeamAndChannelIds.map(_._2)
    activeRows.filter(row => row.slackChannelId.inSet(slackChannelIds)).list.groupBy(channel => (channel.slackTeamId, channel.slackChannelId)).collect {
      case (k, Seq(v)) if slackTeamAndChannelIds.contains(k) => k -> v
    }
  }

  def getByChannelId(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId)(implicit session: RSession): Option[SlackChannel] = {
    activeRows.filter(row => row.slackTeamId === slackTeamId && row.slackChannelId === slackChannelId).firstOption
  }

  def getOrCreate(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, slackChannelName: SlackChannelName)(implicit session: RWSession): SlackChannel = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.slackChannelId === slackChannelId).firstOption match {
      case Some(existing) if existing.isActive =>
        val updated = existing.copy(slackChannelName = slackChannelName)
        if (updated != existing) save(updated) else existing
      case inactiveOpt =>
        save(SlackChannel(
          id = inactiveOpt.map(_.id.get),
          slackTeamId = slackTeamId,
          slackChannelId = slackChannelId,
          slackChannelName = slackChannelName
        ))
    }
  }
  def getRipeForPushingDigestNotification(lastPushOlderThan: DateTime)(implicit session: RSession): Seq[Id[SlackChannel]] = {
    activeRows.filter(row => (row.createdAt < lastPushOlderThan && row.lastNotificationAt.isEmpty) || (row.lastNotificationAt < lastPushOlderThan)).map(_.id).list
  }

  def adminGetChannel(channelId: SlackChannelId)(implicit session: RSession): Option[SlackChannel] = {
    rows.filter(_.slackChannelId === channelId).firstOption
  }
}
