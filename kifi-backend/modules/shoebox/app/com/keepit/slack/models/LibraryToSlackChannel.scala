package com.keepit.slack.models

import com.google.inject.{ Inject, Singleton, ImplementedBy }
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
    channelId: SlackChannelId,
    webhookId: Id[SlackIncomingWebhookInfo],
    libraryId: Id[Library],
    status: SlackIntegrationStatus,
    lastProcessedAt: Option[DateTime],
    lastKeepId: Option[Id[Keep]]) extends ModelWithState[LibraryToSlackChannel] {
  def withId(id: Id[LibraryToSlackChannel]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object LibraryToSlackChannelStates extends States[LibraryToSlackChannel]

@ImplementedBy(classOf[LibraryToSlackChannelRepoImpl])
trait LibraryToSlackChannelRepo extends Repo[LibraryToSlackChannel]

@Singleton
class LibraryToSlackChannelRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[LibraryToSlackChannel] with LibraryToSlackChannelRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val channelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val statusColumnType = SlackIntegrationStatus.columnType(db)

  private def ltsFromDbRow(
    id: Option[Id[LibraryToSlackChannel]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[LibraryToSlackChannel],
    ownerId: Id[User],
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    channelId: SlackChannelId,
    webhookId: Id[SlackIncomingWebhookInfo],
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
      channelId,
      webhookId,
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
    lts.channelId,
    lts.webhookId,
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
    def channelId = column[SlackChannelId]("channel_id", O.NotNull)
    def webhookId = column[Id[SlackIncomingWebhookInfo]]("webhook_id", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def status = column[SlackIntegrationStatus]("status", O.NotNull)
    def lastProcessedAt = column[Option[DateTime]]("last_processed_at", O.Nullable)
    def lastKeepId = column[Option[Id[Keep]]]("last_keep_id", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, ownerId, slackUserId, slackTeamId, channelId, webhookId, libraryId, status, lastProcessedAt, lastKeepId) <> ((ltsFromDbRow _).tupled, ltsToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === LibraryToSlackChannelStates.ACTIVE)
  def table(tag: Tag) = new LibraryToSlackChannelTable(tag)
  initTable()
  override def deleteCache(info: LibraryToSlackChannel)(implicit session: RSession): Unit = {}
  override def invalidateCache(info: LibraryToSlackChannel)(implicit session: RSession): Unit = {}
}
