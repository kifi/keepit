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
  integrationId: Id[LibraryToSlackChannel],
  messageId: Id[Message],
  timestamp: SlackTimestamp,
  text: String)
    extends Model[SlackPushForMessage] with ModelWithState[SlackPushForMessage] {
  def withId(id: Id[SlackPushForMessage]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackPushForMessageStates.ACTIVE
}
object SlackPushForMessageStates extends States[SlackPushForMessage]
object SlackPushForMessage {
  def fromMessage(integration: LibraryToSlackChannel, messageId: Id[Message], pushedMessage: SlackMessageResponse): SlackPushForMessage = {
    SlackPushForMessage(
      slackTeamId = integration.slackTeamId,
      slackChannelId = integration.slackChannelId.get,
      integrationId = integration.id.get,
      messageId = messageId,
      timestamp = pushedMessage.timestamp,
      text = pushedMessage.text
    )
  }
}

case class SlackPushForMessageTimestampKey(integrationId: Id[LibraryToSlackChannel], msgId: Id[Message]) extends Key[SlackTimestamp] {
  override val version = 1
  val namespace = "slack_push_for_message"
  def toKey(): String = s"${integrationId.id}_${msgId.id}"
}

class SlackPushForMessageTimestampCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SlackPushForMessageTimestampKey, SlackTimestamp](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

@ImplementedBy(classOf[SlackPushForMessageRepoImpl])
trait SlackPushForMessageRepo extends Repo[SlackPushForMessage] {
  def intern(model: SlackPushForMessage)(implicit session: RWSession): SlackPushForMessage // interns by (integrationId, messageId)
  def getTimestampFromCache(integrationId: Id[LibraryToSlackChannel], messageId: Id[Message]): Option[SlackTimestamp]
  def dropTimestampFromCache(integrationId: Id[LibraryToSlackChannel], messageId: Id[Message]): Unit
}

@Singleton
class SlackPushForMessageRepoImpl @Inject() (
  val db: DataBaseComponent,
  timestampCache: SlackPushForMessageTimestampCache,
  val clock: Clock)
    extends DbRepo[SlackPushForMessage] with SlackPushForMessageRepo {

  import db.Driver.simple._

  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackChannelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val slackTimestampColumnType = SlackDbColumnTypes.timestamp(db)

  type RepoImpl = SlackPushForMessageTable
  class SlackPushForMessageTable(tag: Tag) extends RepoTable[SlackPushForMessage](db, tag, "slack_push_for_message") {
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackChannelId = column[SlackChannelId]("slack_channel_id", O.NotNull)
    def integrationId = column[Id[LibraryToSlackChannel]]("integration_id", O.NotNull)
    def messageId = column[Id[Message]]("message_id", O.NotNull)
    def timestamp = column[SlackTimestamp]("slack_timestamp", O.NotNull)
    def text = column[String]("text", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, slackTeamId, slackChannelId, integrationId, messageId, timestamp, text) <> ((SlackPushForMessage.apply _).tupled, SlackPushForMessage.unapply)
  }

  def table(tag: Tag) = new SlackPushForMessageTable(tag)
  initTable()

  override def deleteCache(model: SlackPushForMessage)(implicit session: RSession): Unit = {
    timestampCache.remove(SlackPushForMessageTimestampKey(model.integrationId, model.messageId))
  }

  override def invalidateCache(model: SlackPushForMessage)(implicit session: RSession) = {
    timestampCache.set(SlackPushForMessageTimestampKey(model.integrationId, model.messageId), model.timestamp)
  }

  def intern(model: SlackPushForMessage)(implicit session: RWSession): SlackPushForMessage = {
    val existingModel = rows.filter(r => r.integrationId === model.integrationId && r.messageId === model.messageId).firstOption
    save(model.copy(id = existingModel.map(_.id.get)))
  }

  def getTimestampFromCache(integrationId: Id[LibraryToSlackChannel], messageId: Id[Message]): Option[SlackTimestamp] = {
    timestampCache.direct.get(SlackPushForMessageTimestampKey(integrationId, messageId))
  }
  def dropTimestampFromCache(integrationId: Id[LibraryToSlackChannel], messageId: Id[Message]): Unit = {
    timestampCache.direct.remove(SlackPushForMessageTimestampKey(integrationId, messageId))
  }
}
