package com.keepit.slack.models

import javax.crypto.spec.IvParameterSpec

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.crypto.{ ModelWithPublicIdCompanion, ModelWithPublicId }
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
    slackChannelId: Option[SlackChannelId],
    slackChannelName: SlackChannelName,
    libraryId: Id[Library],
    status: SlackIntegrationStatus = SlackIntegrationStatus.Off,
    nextIngestionAt: Option[DateTime] = Some(currentDateTime),
    lastIngestingAt: Option[DateTime] = None,
    lastIngestedAt: Option[DateTime] = None,
    lastMessageTimestamp: Option[SlackMessageTimestamp] = None) extends ModelWithState[SlackChannelToLibrary] with ModelWithPublicId[SlackChannelToLibrary] with SlackIntegration {
  def withId(id: Id[SlackChannelToLibrary]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
  def isActive: Boolean = (state == SlackChannelToLibraryStates.ACTIVE)
  def withStatus(newStatus: SlackIntegrationStatus) = copy(status = newStatus)
  def sanitizeForDelete = copy(state = SlackChannelToLibraryStates.INACTIVE, status = SlackIntegrationStatus.Off)
  def withIngestionComplete(ingestionStartedAt: DateTime) = copy(lastIngestedAt = Some(ingestionStartedAt), lastIngestingAt = None, nextIngestionAt = Some(currentDateTime plusMinutes 10))
}

object SlackChannelToLibraryStates extends States[SlackChannelToLibrary]
object SlackChannelToLibrary extends ModelWithPublicIdCompanion[SlackChannelToLibrary] {
  protected val publicIdPrefix = "sctl"
  protected val publicIdIvSpec = new IvParameterSpec(Array(-52, 55, -75, 41, -105, -48, -35, 2, 29, 39, 85, 107, -43, -63, 15, -23))
}

@ImplementedBy(classOf[SlackChannelToLibraryRepoImpl])
trait SlackChannelToLibraryRepo extends Repo[SlackChannelToLibrary] {
  def getActiveByIds(ids: Set[Id[SlackChannelToLibrary]])(implicit session: RSession): Set[SlackChannelToLibrary]
  def getActiveByOwnerAndLibrary(ownerId: Id[User], libraryId: Id[Library])(implicit session: RSession): Set[SlackChannelToLibrary]
  def getBySlackTeamChannelAndLibrary(slackTeamId: SlackTeamId, slackChannelId: Option[SlackChannelId], libraryId: Id[Library], excludeState: Option[State[SlackChannelToLibrary]] = Some(SlackChannelToLibraryStates.INACTIVE))(implicit session: RSession): Option[SlackChannelToLibrary]
  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationCreateRequest)(implicit session: RWSession): (SlackChannelToLibrary, Boolean)
  def deactivate(model: SlackChannelToLibrary)(implicit session: RWSession): Unit
}

@Singleton
class SlackChannelToLibraryRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[SlackChannelToLibrary] with SlackChannelToLibraryRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackChannelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val slackChannelColumnType = SlackDbColumnTypes.channel(db)
  implicit val slackMessageTimestampColumnType = SlackDbColumnTypes.timestamp(db)
  implicit val statusColumnType = SlackIntegrationStatus.columnType(db)

  private def stlFromDbRow(
    id: Option[Id[SlackChannelToLibrary]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[SlackChannelToLibrary],
    ownerId: Id[User],
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
      ownerId,
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
    stl.ownerId,
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
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
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
    def * = (id.?, createdAt, updatedAt, state, ownerId, slackUserId, slackTeamId, slackChannelId, slackChannelName, libraryId, status, nextIngestionAt, lastIngestingAt, lastIngestedAt, lastMessageTimestamp) <> ((stlFromDbRow _).tupled, stlToDbRow _)
  }

  private def activeRows = rows.filter(row => row.state === SlackChannelToLibraryStates.ACTIVE)
  def table(tag: Tag) = new SlackChannelToLibraryTable(tag)
  initTable()
  override def deleteCache(info: SlackChannelToLibrary)(implicit session: RSession): Unit = {}
  override def invalidateCache(info: SlackChannelToLibrary)(implicit session: RSession): Unit = {}

  def getActiveByIds(ids: Set[Id[SlackChannelToLibrary]])(implicit session: RSession): Set[SlackChannelToLibrary] = {
    activeRows.filter(_.id.inSet(ids)).list.toSet
  }
  def getActiveByOwnerAndLibrary(ownerId: Id[User], libraryId: Id[Library])(implicit session: RSession): Set[SlackChannelToLibrary] = {
    activeRows.filter(row => row.ownerId === ownerId && row.libraryId === libraryId).list.toSet
  }

  def getBySlackTeamChannelAndLibrary(slackTeamId: SlackTeamId, slackChannelId: Option[SlackChannelId], libraryId: Id[Library], excludeState: Option[State[SlackChannelToLibrary]] = Some(SlackChannelToLibraryStates.INACTIVE))(implicit session: RSession): Option[SlackChannelToLibrary] = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.slackChannelId === slackChannelId.orNull && row.libraryId === libraryId && row.state =!= excludeState.orNull).firstOption
  }

  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationCreateRequest)(implicit session: RWSession): (SlackChannelToLibrary, Boolean) = {
    getBySlackTeamChannelAndLibrary(request.slackTeamId, request.slackChannelId, request.libraryId, excludeState = None) match {
      case Some(integration) if integration.isActive =>
        val isIntegrationOwner = integration.ownerId == request.userId && integration.slackUserId == request.slackUserId
        val updated = integration.copy(slackChannelName = request.slackChannel)
        val saved = if (updated == integration) integration else save(updated)
        (saved, isIntegrationOwner)
      case inactiveIntegrationOpt =>
        val newIntegration = SlackChannelToLibrary(
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
  def deactivate(model: SlackChannelToLibrary)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}
