package com.keepit.slack.models

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.discussion.Message
import org.joda.time.DateTime

import scala.concurrent.duration.Duration
import scala.slick.direct.AnnotationMapper.column
import scala.slick.lifted.Tag

case class SlackPushForMessage(
  id: Option[Id[SlackPushForMessage]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackPushForMessage] = SlackPushForMessageStates.ACTIVE,
  slackTeamId: SlackTeamId,
  slackChannelId: SlackChannelId,
  slackUserId: SlackUserId,
  integrationId: Id[LibraryToSlackChannel],
  messageId: Id[Message],
  timestamp: SlackTimestamp,
  text: String,
  lastKnownEditability: SlackMessageEditability,
  messageRequest: Option[SlackMessageRequest])
    extends Model[SlackPushForMessage] with ModelWithState[SlackPushForMessage] {
  def withId(id: Id[SlackPushForMessage]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackPushForMessageStates.ACTIVE

  def uneditable = this.copy(lastKnownEditability = SlackMessageEditability.UNEDITABLE)
  def withMessageRequest(mr: SlackMessageRequest) = this.copy(messageRequest = Some(mr))
  def withTimestamp(newTimestamp: SlackTimestamp) = this.copy(timestamp = newTimestamp)
}
object SlackPushForMessageStates extends States[SlackPushForMessage]
object SlackPushForMessage {
  def fromMessage(integration: LibraryToSlackChannel, messageId: Id[Message], slackUserId: SlackUserId, request: SlackMessageRequest, response: SlackMessageResponse): SlackPushForMessage = {
    SlackPushForMessage(
      slackTeamId = integration.slackTeamId,
      slackChannelId = integration.slackChannelId,
      slackUserId = slackUserId,
      integrationId = integration.id.get,
      messageId = messageId,
      timestamp = response.timestamp,
      text = response.text,
      lastKnownEditability = if (!response.sentByAnonymousBot) SlackMessageEditability.EDITABLE else SlackMessageEditability.UNEDITABLE,
      messageRequest = Some(request)
    )
  }
}

@ImplementedBy(classOf[SlackPushForMessageRepoImpl])
trait SlackPushForMessageRepo extends Repo[SlackPushForMessage] {
  def intern(model: SlackPushForMessage)(implicit session: RWSession): SlackPushForMessage // interns by (integrationId, messageId)
  def getEditableByIntegrationAndKeepIds(integrationId: Id[LibraryToSlackChannel], msgIds: Set[Id[Message]])(implicit session: RSession): Map[Id[Message], SlackPushForMessage]
  def getPushedTimestampsByChannel(channel: SlackChannelId, from: SlackTimestamp)(implicit session: RSession): Set[SlackTimestamp]
}

@Singleton
class SlackPushForMessageRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[SlackPushForMessage] with SlackPushForMessageRepo {

  import db.Driver.simple._

  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackChannelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackTimestampColumnType = SlackDbColumnTypes.timestamp(db)
  implicit val editabilityMapper = MappedColumnType.base[SlackMessageEditability, String](_.value, str => SlackMessageEditability.fromStr(str).get)
  implicit val messageRequestMapper = jsonMapper[SlackMessageRequest]

  type RepoImpl = SlackPushForMessageTable
  class SlackPushForMessageTable(tag: Tag) extends RepoTable[SlackPushForMessage](db, tag, "slack_push_for_message") {
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackChannelId = column[SlackChannelId]("slack_channel_id", O.NotNull)
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def integrationId = column[Id[LibraryToSlackChannel]]("integration_id", O.NotNull)
    def messageId = column[Id[Message]]("message_id", O.NotNull)
    def timestamp = column[SlackTimestamp]("slack_timestamp", O.NotNull)
    def text = column[String]("text", O.NotNull)
    def lastKnownEditability = column[SlackMessageEditability]("last_known_editability", O.NotNull)
    def messageRequest = column[Option[SlackMessageRequest]]("message_request", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, slackTeamId, slackChannelId, slackUserId, integrationId, messageId, timestamp, text, lastKnownEditability, messageRequest) <> ((SlackPushForMessage.apply _).tupled, SlackPushForMessage.unapply)
  }

  private def activeRows = rows.filter(_.state === SlackPushForMessageStates.ACTIVE)
  private def editableRows = activeRows.filter(_.lastKnownEditability === (SlackMessageEditability.EDITABLE: SlackMessageEditability))
  def table(tag: Tag) = new SlackPushForMessageTable(tag)
  initTable()

  override def deleteCache(model: SlackPushForMessage)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: SlackPushForMessage)(implicit session: RSession) = {}

  def intern(model: SlackPushForMessage)(implicit session: RWSession): SlackPushForMessage = {
    val existingModel = rows.filter(r => r.integrationId === model.integrationId && r.messageId === model.messageId).firstOption
    save(model.copy(id = existingModel.map(_.id.get)))
  }
  def getEditableByIntegrationAndKeepIds(integrationId: Id[LibraryToSlackChannel], msgIds: Set[Id[Message]])(implicit session: RSession): Map[Id[Message], SlackPushForMessage] = {
    editableRows.filter(r => r.integrationId === integrationId && r.messageId.inSet(msgIds)).list.map(mp => mp.messageId -> mp).toMap
  }
  def getPushedTimestampsByChannel(channel: SlackChannelId, from: SlackTimestamp)(implicit session: RSession): Set[SlackTimestamp] = {
    activeRows.filter(r => r.slackChannelId === channel && r.timestamp >= from).map(_.timestamp).list.toSet
  }
}
