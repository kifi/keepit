package com.keepit.slack.models

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.time._
import org.joda.time.DateTime

case class KifibotFeedback(
  id: Option[Id[KifibotFeedback]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[KifibotFeedback] = KifibotFeedbackStates.ACTIVE,
  slackUserId: SlackUserId,
  slackTeamId: SlackTeamId,
  kifiBotDmChannel: SlackChannelId,
  lastIngestedMessageTimestamp: Option[SlackTimestamp] = None,
  lastProcessingAt: Option[DateTime] = None,
  lastProcessedAt: Option[DateTime] = None,
  nextIngestionAt: DateTime = currentDateTime)
    extends ModelWithState[KifibotFeedback] {
  def withId(id: Id[KifibotFeedback]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == KifibotFeedbackStates.ACTIVE
}

object KifibotFeedbackStates extends States[KifibotFeedback]

@ImplementedBy(classOf[KifibotFeedbackRepoImpl])
trait KifibotFeedbackRepo extends Repo[KifibotFeedback] {
  def intern(slackTeamId: SlackTeamId, slackUserId: SlackUserId, kifiBotDmChannel: SlackChannelId)(implicit session: RWSession): KifibotFeedback
  def getByIds(ids: Set[Id[KifibotFeedback]])(implicit session: RSession): Map[Id[KifibotFeedback], KifibotFeedback]

  def getRipeForProcessing(limit: Int, overrideProcessesOlderThan: DateTime)(implicit session: RSession): Seq[Id[KifibotFeedback]]
  def markAsProcessing(id: Id[KifibotFeedback], overrideProcessesOlderThan: DateTime)(implicit session: RWSession): Boolean
  def updateLastIngestedMessage(id: Id[KifibotFeedback], timestamp: SlackTimestamp)(implicit session: RWSession): Boolean
  def finishProcessing(id: Id[KifibotFeedback], nextIngestionAt: DateTime)(implicit session: RWSession): Boolean
}

@Singleton
class KifibotFeedbackRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[KifibotFeedback] with KifibotFeedbackRepo {
  // Don't put a cache on this repo, it's just for scheduling and makes extensive use of updates

  private def fromDbRow(
    id: Option[Id[KifibotFeedback]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[KifibotFeedback],
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    kifiBotDmChannel: SlackChannelId,
    lastIngestedMessageTimestamp: Option[SlackTimestamp],
    lastProcessingAt: Option[DateTime],
    lastProcessedAt: Option[DateTime],
    nextIngestionAt: DateTime) = {
    KifibotFeedback(
      id = id,
      createdAt = createdAt,
      updatedAt = updatedAt,
      state = state,
      slackUserId = slackUserId,
      slackTeamId = slackTeamId,
      kifiBotDmChannel = kifiBotDmChannel,
      lastIngestedMessageTimestamp = lastIngestedMessageTimestamp,
      lastProcessingAt = lastProcessingAt,
      lastProcessedAt = lastProcessedAt,
      nextIngestionAt = nextIngestionAt
    )
  }

  private def toDbRow(model: KifibotFeedback) = Some((
    model.id,
    model.createdAt,
    model.updatedAt,
    model.state,
    model.slackUserId,
    model.slackTeamId,
    model.kifiBotDmChannel,
    model.lastIngestedMessageTimestamp,
    model.lastProcessingAt,
    model.lastProcessedAt,
    model.nextIngestionAt
  ))

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackChannelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val slackMessageTimestampColumnType = SlackDbColumnTypes.timestamp(db)

  type RepoImpl = KifibotFeedbackTable

  class KifibotFeedbackTable(tag: Tag) extends RepoTable[KifibotFeedback](db, tag, "slack_kifibot_feedback") {
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def kifiBotDmChannel = column[SlackChannelId]("kifibot_dm_channel", O.NotNull)
    def lastIngestedMessageTimestamp = column[Option[SlackTimestamp]]("last_ingested_message_timestamp", O.Nullable)
    def lastProcessingAt = column[Option[DateTime]]("last_processing_at", O.Nullable)
    def lastProcessedAt = column[Option[DateTime]]("last_processed_at", O.Nullable)
    def nextIngestionAt = column[DateTime]("next_ingestion_at", O.NotNull)
    def * = (
      id.?, createdAt, updatedAt, state, slackUserId, slackTeamId, kifiBotDmChannel,
      lastIngestedMessageTimestamp, lastProcessingAt, lastProcessedAt, nextIngestionAt) <> ((fromDbRow _).tupled, toDbRow)

    def availableForProcessing(overrideDate: DateTime) = lastProcessingAt.isEmpty || lastProcessingAt < overrideDate
  }

  override def deleteCache(model: KifibotFeedback)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: KifibotFeedback)(implicit session: RSession): Unit = {}

  private def activeRows = rows.filter(row => row.state === KifibotFeedbackStates.ACTIVE)
  def table(tag: Tag) = new KifibotFeedbackTable(tag)
  initTable()

  def intern(slackTeamId: SlackTeamId, slackUserId: SlackUserId, kifiBotDmChannel: SlackChannelId)(implicit session: RWSession): KifibotFeedback = {
    val existingModel = rows.filter(r => r.slackTeamId === slackTeamId && r.slackUserId === slackUserId).firstOption
    existingModel match {
      case Some(model) if model.isActive =>
        val updatedModel = model.copy(kifiBotDmChannel = kifiBotDmChannel)
        if (updatedModel != model) save(updatedModel) else model
      case deadModelOpt =>
        save(KifibotFeedback(
          id = deadModelOpt.map(_.id.get),
          slackTeamId = slackTeamId,
          slackUserId = slackUserId,
          kifiBotDmChannel = kifiBotDmChannel
        ))
    }
  }
  def getByIds(ids: Set[Id[KifibotFeedback]])(implicit session: RSession): Map[Id[KifibotFeedback], KifibotFeedback] = {
    activeRows.filter(_.id.inSet(ids)).list.map(m => m.id.get -> m).toMap
  }

  def getRipeForProcessing(limit: Int, overrideProcessesOlderThan: DateTime)(implicit session: RSession): Seq[Id[KifibotFeedback]] = {
    val now = clock.now
    activeRows
      .filter(row => row.nextIngestionAt <= now && row.availableForProcessing(overrideProcessesOlderThan))
      .sortBy(row => row.nextIngestionAt)
      .map(_.id).take(limit).list
  }
  def markAsProcessing(id: Id[KifibotFeedback], overrideProcessesOlderThan: DateTime)(implicit session: RWSession): Boolean = {
    val now = clock.now
    activeRows
      .filter(row => row.id === id && row.availableForProcessing(overrideProcessesOlderThan))
      .map(r => (r.updatedAt, r.lastProcessingAt)).update((now, Some(now))) > 0
  }
  def updateLastIngestedMessage(id: Id[KifibotFeedback], timestamp: SlackTimestamp)(implicit session: RWSession): Boolean = {
    val now = clock.now
    activeRows.filter(_.id === id).map(r => (r.updatedAt, r.lastIngestedMessageTimestamp)).update((now, Some(timestamp))) > 0
  }
  def finishProcessing(id: Id[KifibotFeedback], nextIngestionAt: DateTime)(implicit session: RWSession): Boolean = {
    val now = clock.now
    activeRows.filter(_.id === id)
      .map(r => (r.updatedAt, r.lastProcessingAt, r.lastProcessedAt, r.nextIngestionAt))
      .update((now, None, Some(now), nextIngestionAt)) > 0
  }
}
