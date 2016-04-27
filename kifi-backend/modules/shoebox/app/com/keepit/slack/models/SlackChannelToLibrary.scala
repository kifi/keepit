package com.keepit.slack.models

import javax.crypto.spec.IvParameterSpec

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.crypto.{ PublicIdGenerator, ModelWithPublicId }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.LibrarySpace.{ UserSpace, OrganizationSpace }
import com.keepit.model.{ Organization, LibrarySpace, User, Library }
import org.joda.time.{ Period, DateTime }
import com.keepit.common.core._

case class SlackChannelToLibrary(
  id: Option[Id[SlackChannelToLibrary]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[SlackChannelToLibrary] = SlackChannelToLibraryStates.ACTIVE,
  slackUserId: SlackUserId,
  slackTeamId: SlackTeamId,
  slackChannelId: SlackChannelId,
  libraryId: Id[Library],
  status: SlackIntegrationStatus = SlackIntegrationStatus.Off,
  changedStatusAt: DateTime = currentDateTime,
  nextIngestionAt: Option[DateTime] = None,
  lastIngestingAt: Option[DateTime] = None,
  lastIngestedAt: Option[DateTime] = None,
  lastMessageTimestamp: Option[SlackTimestamp] = None)
    extends ModelWithState[SlackChannelToLibrary] with ModelWithPublicId[SlackChannelToLibrary] with SlackIntegration {
  def withId(id: Id[SlackChannelToLibrary]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackChannelToLibraryStates.ACTIVE
  def isWorking: Boolean = isActive && status == SlackIntegrationStatus.On
  def withStatus(newStatus: SlackIntegrationStatus) = this.copy(
    nextIngestionAt = if (newStatus == SlackIntegrationStatus.On) Some(currentDateTime) else None,
    changedStatusAt = if (status != newStatus) currentDateTime else changedStatusAt,
    status = newStatus
  )
  def sanitizeForDelete = copy(state = SlackChannelToLibraryStates.INACTIVE, status = SlackIntegrationStatus.Off)
}

object SlackChannelToLibraryStates extends States[SlackChannelToLibrary]
object SlackChannelToLibrary extends PublicIdGenerator[SlackChannelToLibrary] {
  protected val publicIdPrefix = "sctl"
  protected val publicIdIvSpec = new IvParameterSpec(Array(-52, 55, -75, 41, -105, -48, -35, 2, 29, 39, 85, 107, -43, -63, 15, -23))
}

@ImplementedBy(classOf[SlackChannelToLibraryRepoImpl])
trait SlackChannelToLibraryRepo extends Repo[SlackChannelToLibrary] {
  def getByIds(ids: Set[Id[SlackChannelToLibrary]])(implicit session: RSession): Map[Id[SlackChannelToLibrary], SlackChannelToLibrary]
  def getActiveByIds(ids: Set[Id[SlackChannelToLibrary]])(implicit session: RSession): Set[SlackChannelToLibrary]
  def getActiveByLibrary(libraryId: Id[Library])(implicit session: RSession): Set[SlackChannelToLibrary]
  def getAllBySlackUserIds(teamId: SlackTeamId, userIds: Set[SlackUserId])(implicit session: RSession): Set[SlackChannelToLibrary]
  def getAllByLibs(libIds: Set[Id[Library]])(implicit session: RSession): Set[SlackChannelToLibrary]
  def getAllBySlackTeamsAndLibraries(slackTeamIds: Set[SlackTeamId], libraryIds: Set[Id[Library]])(implicit session: RSession): Seq[SlackChannelToLibrary]
  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationCreateRequest)(implicit session: RWSession): SlackChannelToLibrary
  def getRipeForIngestion(limit: Int, ingestingForMoreThan: Period)(implicit session: RSession): Seq[Id[SlackChannelToLibrary]]
  def markAsIngesting(ids: Id[SlackChannelToLibrary]*)(implicit session: RWSession): Unit
  def updateLastMessageTimestamp(id: Id[SlackChannelToLibrary], lastMessageTimestamp: SlackTimestamp)(implicit session: RWSession): Unit
  def updateAfterIngestion(id: Id[SlackChannelToLibrary], nextIngestionAt: Option[DateTime], status: SlackIntegrationStatus)(implicit session: RWSession): Unit
  def getBySlackTeam(teamId: SlackTeamId)(implicit session: RSession): Seq[SlackChannelToLibrary]
  def getBySlackTeamAndChannels(teamId: SlackTeamId, channelIds: Set[SlackChannelId])(implicit session: RSession): Map[SlackChannelId, Set[SlackChannelToLibrary]]
  def ingestFromChannelWithin(teamId: SlackTeamId, channelId: SlackChannelId, ingestWithin: Period)(implicit session: RWSession): Int
  def deactivate(model: SlackChannelToLibrary)(implicit session: RWSession): Unit
}

@Singleton
class SlackChannelToLibraryRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    integrationsCache: SlackChannelIntegrationsCache) extends DbRepo[SlackChannelToLibrary] with SlackChannelToLibraryRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackChannelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val slackMessageTimestampColumnType = SlackDbColumnTypes.timestamp(db)
  implicit val statusColumnType = SlackIntegrationStatus.columnType(db)

  private def stlFromDbRow(
    id: Option[Id[SlackChannelToLibrary]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[SlackChannelToLibrary],
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    slackChannelId: SlackChannelId,
    libraryId: Id[Library],
    status: SlackIntegrationStatus,
    changedStatusAt: DateTime,
    nextIngestionAt: Option[DateTime],
    lastIngestingAt: Option[DateTime],
    lastIngestedAt: Option[DateTime],
    lastMessageTimestamp: Option[SlackTimestamp]) = {
    SlackChannelToLibrary(
      id,
      createdAt,
      updatedAt,
      state,
      slackUserId,
      slackTeamId,
      slackChannelId,
      libraryId,
      status,
      changedStatusAt = changedStatusAt,
      nextIngestionAt = nextIngestionAt,
      lastIngestingAt = lastIngestingAt,
      lastIngestedAt = lastIngestedAt,
      lastMessageTimestamp = lastMessageTimestamp
    )
  }

  private def stlToDbRow(stl: SlackChannelToLibrary) = Some((
    stl.id,
    stl.createdAt,
    stl.updatedAt,
    stl.state,
    stl.slackUserId,
    stl.slackTeamId,
    stl.slackChannelId,
    stl.libraryId,
    stl.status,
    stl.changedStatusAt,
    stl.nextIngestionAt,
    stl.lastIngestingAt,
    stl.lastIngestedAt,
    stl.lastMessageTimestamp
  ))

  type RepoImpl = SlackChannelToLibraryTable

  class SlackChannelToLibraryTable(tag: Tag) extends RepoTable[SlackChannelToLibrary](db, tag, "slack_channel_to_library") {
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackChannelId = column[SlackChannelId]("slack_channel_id", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def status = column[SlackIntegrationStatus]("status", O.NotNull)
    def changedStatusAt = column[DateTime]("changed_status_at", O.NotNull)
    def nextIngestionAt = column[Option[DateTime]]("next_ingestion_at", O.Nullable)
    def lastIngestingAt = column[Option[DateTime]]("last_ingesting_at", O.Nullable)
    def lastIngestedAt = column[Option[DateTime]]("last_ingested_at", O.Nullable)
    def lastMessageTimestamp = column[Option[SlackTimestamp]]("last_message_timestamp", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, slackUserId, slackTeamId, slackChannelId, libraryId, status, changedStatusAt, nextIngestionAt, lastIngestingAt, lastIngestedAt, lastMessageTimestamp) <> ((stlFromDbRow _).tupled, stlToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackChannelToLibraryStates.ACTIVE)
  def table(tag: Tag) = new SlackChannelToLibraryTable(tag)
  initTable()
  override def deleteCache(info: SlackChannelToLibrary)(implicit session: RSession): Unit = {
    integrationsCache.remove(SlackChannelIntegrationsKey(info.slackTeamId, info.slackChannelId))
  }
  override def invalidateCache(info: SlackChannelToLibrary)(implicit session: RSession): Unit = deleteCache(info)

  def getByIds(ids: Set[Id[SlackChannelToLibrary]])(implicit session: RSession): Map[Id[SlackChannelToLibrary], SlackChannelToLibrary] = {
    rows.filter(row => row.id.inSet(ids)).list.map(r => (r.id.get, r)).toMap
  }

  def getActiveByIds(ids: Set[Id[SlackChannelToLibrary]])(implicit session: RSession): Set[SlackChannelToLibrary] = {
    activeRows.filter(_.id.inSet(ids)).list.toSet
  }

  def getActiveByLibrary(libraryId: Id[Library])(implicit session: RSession): Set[SlackChannelToLibrary] = {
    activeRows.filter(_.libraryId === libraryId).list.toSet
  }

  def getAllBySlackUserIds(teamId: SlackTeamId, userIds: Set[SlackUserId])(implicit session: RSession): Set[SlackChannelToLibrary] = {
    activeRows.filter(r => r.slackTeamId === teamId && r.slackUserId.inSet(userIds)).list.toSet
  }

  def getAllByLibs(libIds: Set[Id[Library]])(implicit session: RSession): Set[SlackChannelToLibrary] = {
    activeRows.filter(r => r.libraryId inSet libIds).list.toSet
  }

  def getAllBySlackTeamsAndLibraries(slackTeamIds: Set[SlackTeamId], libraryIds: Set[Id[Library]])(implicit session: RSession): Seq[SlackChannelToLibrary] = {
    activeRows.filter(r => r.slackTeamId.inSet(slackTeamIds) && r.libraryId.inSet(libraryIds)).list
  }

  private def getBySlackTeamChannelAndLibrary(slackTeamId: SlackTeamId, slackChannelId: SlackChannelId, libraryId: Id[Library], excludeState: Option[State[SlackChannelToLibrary]] = Some(SlackChannelToLibraryStates.INACTIVE))(implicit session: RSession): Option[SlackChannelToLibrary] = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.slackChannelId === slackChannelId && row.libraryId === libraryId && row.state =!= excludeState.orNull).firstOption
  }

  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationCreateRequest)(implicit session: RWSession): SlackChannelToLibrary = {
    getBySlackTeamChannelAndLibrary(request.slackTeamId, request.slackChannelId, request.libraryId, excludeState = None) match {
      case Some(integration) if integration.isActive =>
        val updatedStatus = if (integration.status == SlackIntegrationStatus.On) integration.status else request.status
        val updated = integration.copy(
          slackUserId = request.slackUserId,
          slackChannelId = request.slackChannelId
        ).withStatus(updatedStatus)
        val saved = if (updated == integration) integration else save(updated)
        saved
      case inactiveIntegrationOpt =>
        val newIntegration = SlackChannelToLibrary(
          id = inactiveIntegrationOpt.flatMap(_.id),
          slackUserId = request.slackUserId,
          slackTeamId = request.slackTeamId,
          slackChannelId = request.slackChannelId,
          libraryId = request.libraryId
        ).withStatus(request.status)
        save(newIntegration)
    }
  }

  def getRipeForIngestion(limit: Int, ingestingForMoreThan: Period)(implicit session: RSession): Seq[Id[SlackChannelToLibrary]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val now = clock.now()
    val lastIngestingTooLongAgo = now minus ingestingForMoreThan

    val q = sql"""
      SELECT id
      FROM `slack_channel_to_library`
      WHERE `state` = 'active' AND `status` = 'on' AND `next_ingestion_at` < $now AND (`last_ingesting_at` IS NULL OR `last_ingesting_at` < $lastIngestingTooLongAgo) ORDER BY slack_team_id IN ('T02A81H50', 'T04SM6T1Z') DESC, `last_ingested_at` IS NULL DESC, `next_ingestion_at` ASC LIMIT $limit;
    """

    q.as[Id[SlackChannelToLibrary]].list
  }

  def markAsIngesting(ids: Id[SlackChannelToLibrary]*)(implicit session: RWSession): Unit = updateLastIngestingAt(ids.toSet, Some(clock.now()))

  private def updateLastIngestingAt(ids: Set[Id[SlackChannelToLibrary]], lastIngestingAt: Option[DateTime])(implicit session: RWSession): Unit = {
    val now = clock.now()
    (for (r <- rows if r.id.inSet(ids.toSet)) yield (r.updatedAt, r.lastIngestingAt)).update((now, lastIngestingAt))
  }

  def updateLastMessageTimestamp(id: Id[SlackChannelToLibrary], lastMessageTimestamp: SlackTimestamp)(implicit session: RWSession): Unit = {
    val now = clock.now()
    (for (r <- rows if r.id === id) yield (r.updatedAt, r.lastMessageTimestamp)).update((now, Some(lastMessageTimestamp)))
  }

  def updateAfterIngestion(id: Id[SlackChannelToLibrary], nextIngestionAt: Option[DateTime], status: SlackIntegrationStatus)(implicit session: RWSession): Unit = {
    val now = clock.now()
    (for (r <- rows if r.id === id) yield (r.updatedAt, r.lastIngestingAt, r.lastIngestedAt, r.nextIngestionAt, r.status)).update((now, None, Some(now), nextIngestionAt, status))
  }

  def getBySlackTeam(teamId: SlackTeamId)(implicit session: RSession): Seq[SlackChannelToLibrary] = {
    activeRows.filter(r => r.slackTeamId === teamId).list
  }

  def getBySlackTeamAndChannels(teamId: SlackTeamId, channelIds: Set[SlackChannelId])(implicit session: RSession): Map[SlackChannelId, Set[SlackChannelToLibrary]] = {
    activeRows.filter(r => r.slackTeamId === teamId && r.slackChannelId.inSet(channelIds)).list.toSet.groupBy(_.slackChannelId)
  }

  def ingestFromChannelWithin(teamId: SlackTeamId, channelId: SlackChannelId, ingestWithin: Period)(implicit session: RWSession): Int = {
    val now = clock.now()
    val nextIngestionAtLatest = now plus ingestWithin
    activeRows.filter(r => r.slackTeamId === teamId && r.slackChannelId === channelId && r.nextIngestionAt > nextIngestionAtLatest).map(r => (r.updatedAt, r.nextIngestionAt)).update((now, Some(nextIngestionAtLatest)))
  }

  def deactivate(model: SlackChannelToLibrary)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}
