package com.keepit.slack.models

import javax.crypto.spec.IvParameterSpec

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.crypto.{ ModelWithPublicIdCompanion, ModelWithPublicId }
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
  space: LibrarySpace,
  slackUserId: SlackUserId,
  slackTeamId: SlackTeamId,
  slackChannelId: Option[SlackChannelId],
  slackChannelName: SlackChannelName,
  libraryId: Id[Library],
  status: SlackIntegrationStatus = SlackIntegrationStatus.Off,
  nextIngestionAt: Option[DateTime] = None,
  lastIngestingAt: Option[DateTime] = None,
  lastIngestedAt: Option[DateTime] = None,
  lastMessageTimestamp: Option[SlackMessageTimestamp] = None)
    extends ModelWithState[SlackChannelToLibrary] with ModelWithPublicId[SlackChannelToLibrary] with ModelWithMaybeCopy[SlackChannelToLibrary] with SlackIntegration {
  def withId(id: Id[SlackChannelToLibrary]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == SlackChannelToLibraryStates.ACTIVE
  def withStatus(newStatus: SlackIntegrationStatus) = copy(status = newStatus, nextIngestionAt = if (newStatus == SlackIntegrationStatus.On) Some(currentDateTime) else None)

  def withModifications(mods: SlackIntegrationModification) = {
    this
      .maybeCopy(_.status, mods.status, _.withStatus)
      .maybeCopy(_.space, mods.space, _.withSpace)
  }
  def sanitizeForDelete = copy(state = SlackChannelToLibraryStates.INACTIVE, status = SlackIntegrationStatus.Off)
  def withSpace(newSpace: LibrarySpace) = this.copy(space = newSpace)
}

object SlackChannelToLibraryStates extends States[SlackChannelToLibrary]
object SlackChannelToLibrary extends ModelWithPublicIdCompanion[SlackChannelToLibrary] {
  protected val publicIdPrefix = "sctl"
  protected val publicIdIvSpec = new IvParameterSpec(Array(-52, 55, -75, 41, -105, -48, -35, 2, 29, 39, 85, 107, -43, -63, 15, -23))
}

@ImplementedBy(classOf[SlackChannelToLibraryRepoImpl])
trait SlackChannelToLibraryRepo extends Repo[SlackChannelToLibrary] {
  def getByIds(ids: Set[Id[SlackChannelToLibrary]])(implicit session: RSession): Map[Id[SlackChannelToLibrary], SlackChannelToLibrary]
  def getActiveByIds(ids: Set[Id[SlackChannelToLibrary]])(implicit session: RSession): Set[SlackChannelToLibrary]
  def getActiveByLibrary(libraryId: Id[Library])(implicit session: RSession): Set[SlackChannelToLibrary]
  def getUserVisibleIntegrationsForLibraries(userId: Id[User], orgsForUser: Set[Id[Organization]], libraryIds: Set[Id[Library]])(implicit session: RSession): Seq[SlackChannelToLibrary]
  def getBySlackTeamChannelAndLibrary(slackTeamId: SlackTeamId, slackChannelName: SlackChannelName, libraryId: Id[Library], excludeState: Option[State[SlackChannelToLibrary]] = Some(SlackChannelToLibraryStates.INACTIVE))(implicit session: RSession): Option[SlackChannelToLibrary]
  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationCreateRequest)(implicit session: RWSession): SlackChannelToLibrary
  def getRipeForIngestion(limit: Int, ingestingForMoreThan: Period)(implicit session: RSession): Seq[Id[SlackChannelToLibrary]]
  def markAsIngesting(ids: Id[SlackChannelToLibrary]*)(implicit session: RWSession): Unit
  def unmarkAsIngesting(ids: Id[SlackChannelToLibrary]*)(implicit session: RWSession): Unit
  def updateLastMessageTimestamp(id: Id[SlackChannelToLibrary], lastMessageTimestamp: SlackMessageTimestamp)(implicit session: RWSession): Unit
  def updateAfterIngestion(id: Id[SlackChannelToLibrary], nextIngestionAt: Option[DateTime], status: SlackIntegrationStatus)(implicit session: RWSession): Unit
  def getBySlackTeamAndChannel(teamId: SlackTeamId, channelId: SlackChannelId)(implicit session: RSession): Seq[SlackChannelToLibrary]
  def getWithMissingChannelId()(implicit session: RSession): Set[(SlackUserId, SlackTeamId, SlackChannelName)]
  def fillInMissingChannelId(userId: SlackUserId, teamId: SlackTeamId, channelName: SlackChannelName, channelId: SlackChannelId)(implicit session: RWSession): Int
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
  implicit val slackChannelNameColumnType = SlackDbColumnTypes.channelName(db)
  implicit val slackMessageTimestampColumnType = SlackDbColumnTypes.timestamp(db)
  implicit val statusColumnType = SlackIntegrationStatus.columnType(db)

  private def stlFromDbRow(
    id: Option[Id[SlackChannelToLibrary]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[SlackChannelToLibrary],
    userId: Option[Id[User]], // either userId or organizationId must be set
    organizationId: Option[Id[Organization]], // either userId or organizationId must be set
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    slackChannelId: Option[SlackChannelId],
    slackChannelName: SlackChannelName,
    libraryId: Id[Library],
    status: SlackIntegrationStatus,
    nextIngestionAt: Option[DateTime],
    lastIngestingAt: Option[DateTime],
    lastIngestedAt: Option[DateTime],
    lastMessageTimestamp: Option[SlackMessageTimestamp]) = {
    SlackChannelToLibrary(
      id,
      createdAt,
      updatedAt,
      state,
      LibrarySpace.fromOptions(userId, organizationId).get,
      slackUserId,
      slackTeamId,
      slackChannelId,
      slackChannelName,
      libraryId,
      status,
      nextIngestionAt,
      lastIngestingAt,
      lastIngestedAt,
      lastMessageTimestamp
    )
  }

  private def stlToDbRow(stl: SlackChannelToLibrary) = Some((
    stl.id,
    stl.createdAt,
    stl.updatedAt,
    stl.state,
    Some(stl.space).collect { case UserSpace(userId) => userId },
    Some(stl.space).collect { case OrganizationSpace(orgId) => orgId },
    stl.slackUserId,
    stl.slackTeamId,
    stl.slackChannelId,
    stl.slackChannelName,
    stl.libraryId,
    stl.status,
    stl.nextIngestionAt,
    stl.lastIngestingAt,
    stl.lastIngestedAt,
    stl.lastMessageTimestamp
  ))

  type RepoImpl = SlackChannelToLibraryTable

  class SlackChannelToLibraryTable(tag: Tag) extends RepoTable[SlackChannelToLibrary](db, tag, "slack_channel_to_library") {
    def userId = column[Option[Id[User]]]("owner_id", O.Nullable) // TODO(ryan): rename "owner_id" to "user_id"
    def organizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackChannelId = column[Option[SlackChannelId]]("slack_channel_id", O.Nullable)
    def slackChannelName = column[SlackChannelName]("slack_channel_name", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def status = column[SlackIntegrationStatus]("status", O.NotNull)
    def nextIngestionAt = column[Option[DateTime]]("next_ingestion_at", O.Nullable)
    def lastIngestingAt = column[Option[DateTime]]("last_ingesting_at", O.Nullable)
    def lastIngestedAt = column[Option[DateTime]]("last_ingested_at", O.Nullable)
    def lastMessageTimestamp = column[Option[SlackMessageTimestamp]]("last_message_timestamp", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, userId, organizationId, slackUserId, slackTeamId, slackChannelId, slackChannelName, libraryId, status, nextIngestionAt, lastIngestingAt, lastIngestedAt, lastMessageTimestamp) <> ((stlFromDbRow _).tupled, stlToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackChannelToLibraryStates.ACTIVE)
  def table(tag: Tag) = new SlackChannelToLibraryTable(tag)
  initTable()
  override def deleteCache(info: SlackChannelToLibrary)(implicit session: RSession): Unit = {
    info.slackChannelId.foreach(channelId => integrationsCache.remove(SlackChannelIntegrationsKey(info.slackTeamId, channelId)))
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

  def getUserVisibleIntegrationsForLibraries(userId: Id[User], orgsForUser: Set[Id[Organization]], libraryIds: Set[Id[Library]])(implicit session: RSession): Seq[SlackChannelToLibrary] = {
    // TODO(ryan): this `row.organizationId.isEmpty` should not be necessary anymore. If the userId is set, it _should_ imply that the orgId is not
    activeRows.filter(row => row.libraryId.inSet(libraryIds) && row.organizationId.inSet(orgsForUser) || (row.userId === userId && row.organizationId.isEmpty)).list
  }

  def getBySlackTeamChannelAndLibrary(slackTeamId: SlackTeamId, slackChannelName: SlackChannelName, libraryId: Id[Library], excludeState: Option[State[SlackChannelToLibrary]] = Some(SlackChannelToLibraryStates.INACTIVE))(implicit session: RSession): Option[SlackChannelToLibrary] = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.slackChannelName === slackChannelName && row.libraryId === libraryId && row.state =!= excludeState.orNull).firstOption
  }

  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationCreateRequest)(implicit session: RWSession): SlackChannelToLibrary = {
    getBySlackTeamChannelAndLibrary(request.slackTeamId, request.slackChannelName, request.libraryId, excludeState = None) match {
      case Some(integration) if integration.isActive =>
        val updated = integration.copy(space = request.space, slackChannelName = request.slackChannelName)
        val saved = if (updated == integration) integration else save(updated)
        saved
      case inactiveIntegrationOpt =>
        val newIntegration = SlackChannelToLibrary(
          id = inactiveIntegrationOpt.flatMap(_.id),
          space = request.space,
          slackUserId = request.slackUserId,
          slackTeamId = request.slackTeamId,
          slackChannelId = request.slackChannelId,
          slackChannelName = request.slackChannelName,
          libraryId = request.libraryId
        )
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
      WHERE `state` = 'active' AND `status` = 'on' AND `next_ingestion_at` < $now AND (`last_ingesting_at` IS NULL OR `last_ingesting_at` < $lastIngestingTooLongAgo) ORDER BY `last_ingested_at`, `next_ingestion_at` LIMIT $limit;
    """
    q.as[Id[SlackChannelToLibrary]].list
  }

  def markAsIngesting(ids: Id[SlackChannelToLibrary]*)(implicit session: RWSession): Unit = updateLastIngestingAt(ids.toSet, Some(clock.now()))
  def unmarkAsIngesting(ids: Id[SlackChannelToLibrary]*)(implicit session: RWSession): Unit = updateLastIngestingAt(ids.toSet, None)

  private def updateLastIngestingAt(ids: Set[Id[SlackChannelToLibrary]], lastIngestingAt: Option[DateTime])(implicit session: RWSession): Unit = {
    val now = clock.now()
    (for (r <- rows if r.id.inSet(ids.toSet)) yield (r.updatedAt, r.lastIngestingAt)).update((now, lastIngestingAt))
  }

  def updateLastMessageTimestamp(id: Id[SlackChannelToLibrary], lastMessageTimestamp: SlackMessageTimestamp)(implicit session: RWSession): Unit = {
    val now = clock.now()
    (for (r <- rows if r.id === id) yield (r.updatedAt, r.lastMessageTimestamp)).update((now, Some(lastMessageTimestamp)))
  }

  def updateAfterIngestion(id: Id[SlackChannelToLibrary], nextIngestionAt: Option[DateTime], status: SlackIntegrationStatus)(implicit session: RWSession): Unit = {
    val now = clock.now()
    (for (r <- rows if r.id === id) yield (r.updatedAt, r.lastIngestingAt, r.lastIngestedAt, r.nextIngestionAt, r.status)).update((now, None, Some(now), nextIngestionAt, status))
  }

  def getBySlackTeamAndChannel(teamId: SlackTeamId, channelId: SlackChannelId)(implicit session: RSession): Seq[SlackChannelToLibrary] = {
    activeRows.filter(r => r.slackTeamId === teamId && r.slackChannelId === channelId).list
  }

  def getWithMissingChannelId()(implicit session: RSession): Set[(SlackUserId, SlackTeamId, SlackChannelName)] = {
    activeRows.filter(_.slackChannelId.isEmpty).map(r => (r.slackUserId, r.slackTeamId, r.slackChannelName)).list.toSet
  }

  def fillInMissingChannelId(userId: SlackUserId, teamId: SlackTeamId, channelName: SlackChannelName, channelId: SlackChannelId)(implicit session: RWSession): Int = {
    activeRows.filter(r => r.slackUserId === userId && r.slackTeamId === teamId && r.slackChannelName === channelName && r.slackChannelId.isEmpty).map(r => (r.updatedAt, r.slackChannelId)).update((clock.now, Some(channelId))) tap { updated =>
      if (updated > 0) integrationsCache.remove(SlackChannelIntegrationsKey(teamId, channelId))
    }
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
