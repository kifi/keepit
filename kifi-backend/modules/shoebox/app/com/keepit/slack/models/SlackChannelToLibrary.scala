package com.keepit.slack.models

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ ModelWithState, Id, State, States }
import com.keepit.common.time._
import com.keepit.model.{ User, Library }
import org.joda.time.DateTime

case class SlackChannelToLibrary(
    id: Option[Id[SlackChannelToLibrary]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[SlackChannelToLibrary] = SlackChannelToLibraryStates.ACTIVE,
    ownerId: Id[User],
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    channelId: SlackChannelId,
    channel: SlackChannel,
    libraryId: Id[Library],
    status: SlackIntegrationStatus = SlackIntegrationStatus.On,
    lastProcessedAt: Option[DateTime] = None,
    lastMessageAt: Option[DateTime] = None) extends ModelWithState[SlackChannelToLibrary] with SlackIntegration {
  def withId(id: Id[SlackChannelToLibrary]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = (state == SlackChannelToLibraryStates.ACTIVE)
}

object SlackChannelToLibraryStates extends States[SlackChannelToLibrary]

@ImplementedBy(classOf[SlackChannelToLibraryRepoImpl])
trait SlackChannelToLibraryRepo extends Repo[SlackChannelToLibrary] {
  def getBySlackTeamChannelAndLibrary(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, libraryId: Id[Library], excludeState: Option[State[SlackChannelToLibrary]] = Some(SlackChannelToLibraryStates.INACTIVE))(implicit session: RSession): Option[SlackChannelToLibrary]
  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationRequest)(implicit session: RWSession): (SlackChannelToLibrary, Boolean)
}

@Singleton
class SlackChannelToLibraryRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[SlackChannelToLibrary] with SlackChannelToLibraryRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val channelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val channelColumnType = SlackDbColumnTypes.channel(db)
  implicit val statusColumnType = SlackIntegrationStatus.columnType(db)

  private def stlFromDbRow(
    id: Option[Id[SlackChannelToLibrary]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[SlackChannelToLibrary],
    ownerId: Id[User],
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    channelId: SlackChannelId,
    channel: SlackChannel,
    libraryId: Id[Library],
    status: SlackIntegrationStatus,
    lastProcessedAt: Option[DateTime],
    lastMessageAt: Option[DateTime]) = {
    SlackChannelToLibrary(
      id,
      createdAt,
      updatedAt,
      state,
      ownerId,
      slackUserId,
      slackTeamId,
      channelId,
      channel,
      libraryId,
      status,
      lastProcessedAt,
      lastMessageAt
    )
  }

  private def stlToDbRow(stl: SlackChannelToLibrary) = Some((
    stl.id,
    stl.createdAt,
    stl.updatedAt,
    stl.state,
    stl.ownerId,
    stl.slackUserId,
    stl.slackTeamId,
    stl.channelId,
    stl.channel,
    stl.libraryId,
    stl.status,
    stl.lastProcessedAt,
    stl.lastMessageAt
  ))

  type RepoImpl = SlackChannelToLibraryTable

  class SlackChannelToLibraryTable(tag: Tag) extends RepoTable[SlackChannelToLibrary](db, tag, "slack_channel_to_library") {
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def channelId = column[SlackChannelId]("channel_id", O.NotNull)
    def channel = column[SlackChannel]("channel", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def status = column[SlackIntegrationStatus]("status", O.NotNull)
    def lastProcessedAt = column[Option[DateTime]]("last_processed_at", O.Nullable)
    def lastMessageAt = column[Option[DateTime]]("last_message_at", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, ownerId, slackUserId, slackTeamId, channelId, channel, libraryId, status, lastProcessedAt, lastMessageAt) <> ((stlFromDbRow _).tupled, stlToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackChannelToLibraryStates.ACTIVE)
  def table(tag: Tag) = new SlackChannelToLibraryTable(tag)
  initTable()
  override def deleteCache(info: SlackChannelToLibrary)(implicit session: RSession): Unit = {}
  override def invalidateCache(info: SlackChannelToLibrary)(implicit session: RSession): Unit = {}

  def getBySlackTeamChannelAndLibrary(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, libraryId: Id[Library], excludeState: Option[State[SlackChannelToLibrary]] = Some(SlackChannelToLibraryStates.INACTIVE))(implicit session: RSession): Option[SlackChannelToLibrary] = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.channelId === slackChannelId && row.libraryId === libraryId && row.state =!= excludeState.orNull).firstOption
  }

  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationRequest)(implicit session: RWSession): (SlackChannelToLibrary, Boolean) = {
    getBySlackTeamChannelAndLibrary(request.slackTeamId, request.channelId, request.libraryId, excludeState = None) match {
      case Some(integration) if integration.isActive =>
        val isIntegrationOwner = (integration.ownerId == request.userId && integration.slackUserId == request.slackUserId)
        val updated = integration.copy(channel = request.channel)
        val saved = if (updated == integration) integration else save(updated)
        (saved, isIntegrationOwner)
      case inactiveIntegrationOpt =>
        val newIntegration = SlackChannelToLibrary(
          id = inactiveIntegrationOpt.flatMap(_.id),
          ownerId = request.userId,
          slackUserId = request.slackUserId,
          slackTeamId = request.slackTeamId,
          channelId = request.channelId,
          channel = request.channel,
          libraryId = request.libraryId
        )
        (save(newIntegration), true)
    }
  }
}
