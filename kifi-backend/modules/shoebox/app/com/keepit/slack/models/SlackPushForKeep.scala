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
  integrationId: Id[LibraryToSlackChannel],
  keepId: Id[Keep],
  timestamp: SlackTimestamp,
  text: String)
    extends Model[SlackPushForKeep] with ModelWithState[SlackPushForKeep] {
  def withId(id: Id[SlackPushForKeep]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackPushForKeepStates.ACTIVE
}
object SlackPushForKeepStates extends States[SlackPushForKeep]

case class SlackPushForKeepTimestampKey(integrationId: Id[LibraryToSlackChannel], keepId: Id[Keep]) extends Key[SlackTimestamp] {
  override val version = 1
  val namespace = "slack_push_for_keep"
  def toKey(): String = s"${integrationId.id}_${keepId.id}"
}

class SlackPushForKeepTimestampCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SlackPushForKeepTimestampKey, SlackTimestamp](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

@ImplementedBy(classOf[SlackPushForKeepRepoImpl])
trait SlackPushForKeepRepo extends Repo[SlackPushForKeep] {
  def intern(model: SlackPushForKeep)(implicit session: RWSession): SlackPushForKeep // interns by (integrationId, keepId)
}

@Singleton
class SlackPushForKeepRepoImpl @Inject() (
  val db: DataBaseComponent,
  timestampCache: SlackPushForKeepTimestampCache,
  val clock: Clock)
    extends DbRepo[SlackPushForKeep] with SlackPushForKeepRepo {

  import db.Driver.simple._

  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackChannelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val slackTimestampColumnType = SlackDbColumnTypes.timestamp(db)

  type RepoImpl = SlackPushForKeepTable
  class SlackPushForKeepTable(tag: Tag) extends RepoTable[SlackPushForKeep](db, tag, "slack_push_for_keep") {
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackChannelId = column[SlackChannelId]("slack_team_id", O.NotNull)
    def integrationId = column[Id[LibraryToSlackChannel]]("integration_id", O.NotNull)
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def timestamp = column[SlackTimestamp]("slack_timestamp", O.NotNull)
    def text = column[String]("text", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, slackTeamId, slackChannelId, integrationId, keepId, timestamp, text) <> ((SlackPushForKeep.apply _).tupled, SlackPushForKeep.unapply)
  }

  def table(tag: Tag) = new SlackPushForKeepTable(tag)
  initTable()

  override def deleteCache(model: SlackPushForKeep)(implicit session: RSession): Unit = {
    timestampCache.remove(SlackPushForKeepTimestampKey(model.integrationId, model.keepId))
  }

  override def invalidateCache(model: SlackPushForKeep)(implicit session: RSession) = {
    timestampCache.set(SlackPushForKeepTimestampKey(model.integrationId, model.keepId), model.timestamp)
  }

  def intern(model: SlackPushForKeep)(implicit session: RWSession): SlackPushForKeep = {
    val existingModel = rows.filter(r => r.integrationId === model.integrationId && r.keepId === model.keepId).firstOption
    save(model.copy(id = existingModel.map(_.id.get)))
  }
}
