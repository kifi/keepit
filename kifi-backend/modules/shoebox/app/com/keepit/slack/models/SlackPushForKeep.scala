package com.keepit.slack.models

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.model.Keep
import org.joda.time.DateTime

import scala.concurrent.duration.Duration

case class SlackPushForKeep(
  id: Option[Id[SlackPushForKeep]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackPushForKeep] = SlackPushForKeepStates.ACTIVE,
  slackTeamId: SlackTeamId,
  slackChannelId: SlackChannelId,
  slackUserId: SlackUserId, // the user whose token was used to push
  integrationId: Id[LibraryToSlackChannel],
  keepId: Id[Keep],
  timestamp: SlackTimestamp,
  text: String,
  lastKnownEditability: SlackMessageEditability,
  messageRequest: Option[SlackMessageRequest])
    extends Model[SlackPushForKeep] with ModelWithState[SlackPushForKeep] {
  def withId(id: Id[SlackPushForKeep]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackPushForKeepStates.ACTIVE
  def isEditable: Boolean = lastKnownEditability == SlackMessageEditability.EDITABLE

  def uneditable = this.copy(lastKnownEditability = SlackMessageEditability.UNEDITABLE)
  def withMessageRequest(mr: SlackMessageRequest) = this.copy(messageRequest = Some(mr))
  def withTimestamp(newTimestamp: SlackTimestamp) = this.copy(timestamp = newTimestamp)
}
object SlackPushForKeepStates extends States[SlackPushForKeep]
object SlackPushForKeep {
  def fromMessage(integration: LibraryToSlackChannel, keepId: Id[Keep], slackUserId: SlackUserId, request: SlackMessageRequest, response: SlackMessageResponse): SlackPushForKeep = {
    SlackPushForKeep(
      slackTeamId = integration.slackTeamId,
      slackChannelId = integration.slackChannelId,
      slackUserId = slackUserId,
      integrationId = integration.id.get,
      keepId = keepId,
      timestamp = response.timestamp,
      text = response.text,
      lastKnownEditability = if (!response.sentByAnonymousBot) SlackMessageEditability.EDITABLE else SlackMessageEditability.UNEDITABLE,
      messageRequest = Some(request)
    )
  }
}

@ImplementedBy(classOf[SlackPushForKeepRepoImpl])
trait SlackPushForKeepRepo extends Repo[SlackPushForKeep] {
  def intern(model: SlackPushForKeep)(implicit session: RWSession): SlackPushForKeep // interns by (integrationId, keepId)
  def getEditableByIntegrationAndKeepIds(integrationId: Id[LibraryToSlackChannel], keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], SlackPushForKeep]
  def getPushedTimestampsByChannel(channel: SlackChannelId, from: SlackTimestamp)(implicit session: RSession): Set[SlackTimestamp]
}

@Singleton
class SlackPushForKeepRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[SlackPushForKeep] with SlackPushForKeepRepo {

  import db.Driver.simple._

  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackChannelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackTimestampColumnType = SlackDbColumnTypes.timestamp(db)
  implicit val editabilityMapper = MappedColumnType.base[SlackMessageEditability, String](_.value, str => SlackMessageEditability.fromStr(str).get)
  implicit val messageRequestMapper = jsonMapper[SlackMessageRequest]

  type RepoImpl = SlackPushForKeepTable
  class SlackPushForKeepTable(tag: Tag) extends RepoTable[SlackPushForKeep](db, tag, "slack_push_for_keep") {
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackChannelId = column[SlackChannelId]("slack_channel_id", O.NotNull)
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def integrationId = column[Id[LibraryToSlackChannel]]("integration_id", O.NotNull)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def timestamp = column[SlackTimestamp]("slack_timestamp", O.NotNull)
    def text = column[String]("text", O.NotNull)
    def lastKnownEditability = column[SlackMessageEditability]("last_known_editability", O.NotNull)
    def messageRequest = column[Option[SlackMessageRequest]]("message_request", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, slackTeamId, slackChannelId, slackUserId, integrationId, keepId, timestamp, text, lastKnownEditability, messageRequest) <> ((SlackPushForKeep.apply _).tupled, SlackPushForKeep.unapply)
  }

  private def activeRows = rows.filter(_.state === SlackPushForKeepStates.ACTIVE)
  private def editableRows = activeRows.filter(_.lastKnownEditability === (SlackMessageEditability.EDITABLE: SlackMessageEditability))
  def table(tag: Tag) = new SlackPushForKeepTable(tag)
  initTable()

  override def deleteCache(model: SlackPushForKeep)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: SlackPushForKeep)(implicit session: RSession) = {}

  def intern(model: SlackPushForKeep)(implicit session: RWSession): SlackPushForKeep = {
    val existingModel = rows.filter(r => r.integrationId === model.integrationId && r.keepId === model.keepId).firstOption
    save(model.copy(id = existingModel.map(_.id.get)))
  }
  def getEditableByIntegrationAndKeepIds(integrationId: Id[LibraryToSlackChannel], keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], SlackPushForKeep] = {
    editableRows.filter(r => r.integrationId === integrationId && r.keepId.inSet(keepIds)).list.map(mp => mp.keepId -> mp).toMap
  }
  def getPushedTimestampsByChannel(channel: SlackChannelId, from: SlackTimestamp)(implicit session: RSession): Set[SlackTimestamp] = {
    activeRows.filter(r => r.slackChannelId === channel && r.timestamp >= from).map(_.timestamp).list.toSet
  }
}
