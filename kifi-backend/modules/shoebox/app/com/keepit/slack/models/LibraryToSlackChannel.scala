package com.keepit.slack.models

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ ModelWithState, Id, State, States }
import com.keepit.common.time._
import com.keepit.model.{ User, Keep, Library }
import org.joda.time.DateTime

case class LibraryToSlackChannel(
    id: Option[Id[LibraryToSlackChannel]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryToSlackChannel] = LibraryToSlackChannelStates.ACTIVE,
    ownerId: Id[User],
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    slackChannelId: SlackChannelId,
    slackChannelName: SlackChannelName,
    libraryId: Id[Library],
    status: SlackIntegrationStatus = SlackIntegrationStatus.On,
    lastProcessedAt: Option[DateTime] = None,
    lastKeepId: Option[Id[Keep]] = None) extends ModelWithState[LibraryToSlackChannel] with SlackIntegration {
  def withId(id: Id[LibraryToSlackChannel]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = (state == LibraryToSlackChannelStates.ACTIVE)
}

object LibraryToSlackChannelStates extends States[LibraryToSlackChannel]

@ImplementedBy(classOf[LibraryToSlackChannelRepoImpl])
trait LibraryToSlackChannelRepo extends Repo[LibraryToSlackChannel] {
  def getBySlackTeamChannelAndLibrary(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, libraryId: Id[Library], excludeState: Option[State[LibraryToSlackChannel]] = Some(LibraryToSlackChannelStates.INACTIVE))(implicit session: RSession): Option[LibraryToSlackChannel]
  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationRequest)(implicit session: RWSession): (LibraryToSlackChannel, Boolean)
}

@Singleton
class LibraryToSlackChannelRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[LibraryToSlackChannel] with LibraryToSlackChannelRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackChannelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val slackChannelColumnType = SlackDbColumnTypes.channel(db)
  implicit val statusColumnType = SlackIntegrationStatus.columnType(db)

  private def ltsFromDbRow(
    id: Option[Id[LibraryToSlackChannel]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[LibraryToSlackChannel],
    ownerId: Id[User],
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    slackChannelId: SlackChannelId,
    slackChannelName: SlackChannelName,
    libraryId: Id[Library],
    status: SlackIntegrationStatus,
    lastProcessedAt: Option[DateTime],
    lastKeepId: Option[Id[Keep]]) = {
    LibraryToSlackChannel(
      id,
      createdAt,
      updatedAt,
      state,
      ownerId,
      slackUserId,
      slackTeamId,
      slackChannelId,
      slackChannelName,
      libraryId,
      status,
      lastProcessedAt,
      lastKeepId
    )
  }

  private def ltsToDbRow(lts: LibraryToSlackChannel) = Some((
    lts.id,
    lts.createdAt,
    lts.updatedAt,
    lts.state,
    lts.ownerId,
    lts.slackUserId,
    lts.slackTeamId,
    lts.slackChannelId,
    lts.slackChannelName,
    lts.libraryId,
    lts.status,
    lts.lastProcessedAt,
    lts.lastKeepId
  ))

  type RepoImpl = LibraryToSlackChannelTable

  class LibraryToSlackChannelTable(tag: Tag) extends RepoTable[LibraryToSlackChannel](db, tag, "library_to_slack_channel") {
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackChannelId = column[SlackChannelId]("slack_channel_id", O.NotNull)
    def slackChannelName = column[SlackChannelName]("slack_channel_name", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def status = column[SlackIntegrationStatus]("status", O.NotNull)
    def lastProcessedAt = column[Option[DateTime]]("last_processed_at", O.Nullable)
    def lastKeepId = column[Option[Id[Keep]]]("last_keep_id", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, ownerId, slackUserId, slackTeamId, slackChannelId, slackChannelName, libraryId, status, lastProcessedAt, lastKeepId) <> ((ltsFromDbRow _).tupled, ltsToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === LibraryToSlackChannelStates.ACTIVE)
  def table(tag: Tag) = new LibraryToSlackChannelTable(tag)
  initTable()
  override def deleteCache(info: LibraryToSlackChannel)(implicit session: RSession): Unit = {}
  override def invalidateCache(info: LibraryToSlackChannel)(implicit session: RSession): Unit = {}

  def getBySlackTeamChannelAndLibrary(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, libraryId: Id[Library], excludeState: Option[State[LibraryToSlackChannel]] = Some(LibraryToSlackChannelStates.INACTIVE))(implicit session: RSession): Option[LibraryToSlackChannel] = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.slackChannelId === slackChannelId && row.libraryId === libraryId && row.state =!= excludeState.orNull).firstOption
  }

  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationRequest)(implicit session: RWSession): (LibraryToSlackChannel, Boolean) = {
    getBySlackTeamChannelAndLibrary(request.slackTeamId, request.slackChannelId, request.libraryId, excludeState = None) match {
      case Some(integration) if integration.isActive =>
        val isIntegrationOwner = (integration.ownerId == request.userId && integration.slackUserId == request.slackUserId)
        val updated = integration.copy(slackChannelName = request.slackChannel)
        val saved = if (updated == integration) integration else save(updated)
        (saved, isIntegrationOwner)
      case inactiveIntegrationOpt =>
        val newIntegration = LibraryToSlackChannel(
          id = inactiveIntegrationOpt.flatMap(_.id),
          ownerId = request.userId,
          slackUserId = request.slackUserId,
          slackTeamId = request.slackTeamId,
          slackChannelId = request.slackChannelId,
          slackChannelName = request.slackChannel,
          libraryId = request.libraryId
        )
        (save(newIntegration), true)
    }
  }

}
