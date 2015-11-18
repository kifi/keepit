package com.keepit.slack.models

import javax.crypto.spec.IvParameterSpec

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.crypto.{ ModelWithPublicIdCompanion, ModelWithPublicId }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ ModelWithState, Id, State, States }
import com.keepit.common.time._
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import org.joda.time.{ Period, DateTime }

case class LibraryToSlackChannel(
    id: Option[Id[LibraryToSlackChannel]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryToSlackChannel] = LibraryToSlackChannelStates.ACTIVE,
    ownerId: Id[User],
    space: LibrarySpace,
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    slackChannelId: Option[SlackChannelId],
    slackChannelName: SlackChannelName,
    libraryId: Id[Library],
    status: SlackIntegrationStatus = SlackIntegrationStatus.On,
    lastProcessedAt: Option[DateTime] = None,
    lastProcessedKeep: Option[Id[KeepToLibrary]] = None,
    lastProcessingAt: Option[DateTime] = None) extends ModelWithState[LibraryToSlackChannel] with ModelWithPublicId[LibraryToSlackChannel] with SlackIntegration {
  def withId(id: Id[LibraryToSlackChannel]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == LibraryToSlackChannelStates.ACTIVE
  def withStatus(newStatus: SlackIntegrationStatus) = this.copy(status = newStatus)
  def sanitizeForDelete = this.copy(state = LibraryToSlackChannelStates.INACTIVE, status = SlackIntegrationStatus.Off)
  def withLastProcessedAt(time: DateTime) = this.copy(lastProcessedAt = Some(time))
  def withLastProcessedKeep(ktlId: Option[Id[KeepToLibrary]]) = this.copy(lastProcessedKeep = ktlId)
  def finishedProcessing: LibraryToSlackChannel = this.copy(lastProcessedAt = lastProcessingAt, lastProcessingAt = None)
}

object LibraryToSlackChannelStates extends States[LibraryToSlackChannel]
object LibraryToSlackChannel extends ModelWithPublicIdCompanion[LibraryToSlackChannel] {
  protected val publicIdPrefix = "ltsc"
  protected val publicIdIvSpec = new IvParameterSpec(Array(-64, -39, 101, -61, 12, 125, 99, 20, -14, 28, -92, -120, 79, 50, -126, 18))
}

@ImplementedBy(classOf[LibraryToSlackChannelRepoImpl])
trait LibraryToSlackChannelRepo extends Repo[LibraryToSlackChannel] {
  def getActiveByIds(ids: Set[Id[LibraryToSlackChannel]])(implicit session: RSession): Set[LibraryToSlackChannel]
  def getActiveByOwnerAndLibrary(ownerId: Id[User], libraryId: Id[Library])(implicit session: RSession): Set[LibraryToSlackChannel]
  def getBySlackTeamChannelAndLibrary(slackTeamId: SlackTeamId, slackChannelName: SlackChannelName, libraryId: Id[Library], excludeState: Option[State[LibraryToSlackChannel]] = Some(LibraryToSlackChannelStates.INACTIVE))(implicit session: RSession): Option[LibraryToSlackChannel]
  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationCreateRequest)(implicit session: RWSession): (LibraryToSlackChannel, Boolean)

  def deactivate(model: LibraryToSlackChannel)(implicit session: RWSession): Unit

  def getLibrariesRipeForProcessing(limit: Limit, overrideProcessesOlderThan: DateTime)(implicit session: RSession): Seq[Id[Library]]
  def getIntegrationsRipeForProcessingByLibrary(libraryId: Id[Library], overrideProcessesOlderThan: DateTime)(implicit session: RWSession): Seq[Id[LibraryToSlackChannel]]
  def markAsProcessing(id: Id[LibraryToSlackChannel])(implicit session: RWSession): Option[LibraryToSlackChannel]
  def finishProcessing(model: LibraryToSlackChannel)(implicit session: RWSession): Unit
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
  implicit val slackChannelColumnType = SlackDbColumnTypes.channelName(db)
  implicit val statusColumnType = SlackIntegrationStatus.columnType(db)

  private def ltsFromDbRow(
    id: Option[Id[LibraryToSlackChannel]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[LibraryToSlackChannel],
    ownerId: Id[User],
    organizationId: Option[Id[Organization]],
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    slackChannelId: Option[SlackChannelId],
    slackChannelName: SlackChannelName,
    libraryId: Id[Library],
    status: SlackIntegrationStatus,
    lastProcessedAt: Option[DateTime],
    lastProcessedKeep: Option[Id[KeepToLibrary]],
    startedProcessingAt: Option[DateTime]) = {
    LibraryToSlackChannel(
      id,
      createdAt,
      updatedAt,
      state,
      ownerId,
      LibrarySpace(ownerId, organizationId),
      slackUserId,
      slackTeamId,
      slackChannelId,
      slackChannelName,
      libraryId,
      status,
      lastProcessedAt,
      lastProcessedKeep,
      startedProcessingAt
    )
  }

  private def ltsToDbRow(lts: LibraryToSlackChannel) = Some((
    lts.id,
    lts.createdAt,
    lts.updatedAt,
    lts.state,
    lts.ownerId,
    lts.space match {
      case OrganizationSpace(orgId) => Some(orgId)
      case UserSpace(_) => None
    },
    lts.slackUserId,
    lts.slackTeamId,
    lts.slackChannelId,
    lts.slackChannelName,
    lts.libraryId,
    lts.status,
    lts.lastProcessedAt,
    lts.lastProcessedKeep,
    lts.lastProcessingAt
  ))

  type RepoImpl = LibraryToSlackChannelTable

  class LibraryToSlackChannelTable(tag: Tag) extends RepoTable[LibraryToSlackChannel](db, tag, "library_to_slack_channel") {
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    def organizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackChannelId = column[Option[SlackChannelId]]("slack_channel_id", O.Nullable)
    def slackChannelName = column[SlackChannelName]("slack_channel_name", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def status = column[SlackIntegrationStatus]("status", O.NotNull)
    def lastProcessedAt = column[Option[DateTime]]("last_processed_at", O.Nullable)
    def lastProcessedKeep = column[Option[Id[KeepToLibrary]]]("last_processed_ktl", O.Nullable)
    def lastProcessingAt = column[Option[DateTime]]("last_processing_at", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, ownerId, organizationId, slackUserId, slackTeamId, slackChannelId, slackChannelName, libraryId, status, lastProcessedAt, lastProcessedKeep, lastProcessingAt) <> ((ltsFromDbRow _).tupled, ltsToDbRow _)

    def availableForProcessing(overrideDate: DateTime) = lastProcessingAt.isEmpty || lastProcessingAt < overrideDate
  }

  def table(tag: Tag) = new LibraryToSlackChannelTable(tag)
  initTable()
  override def deleteCache(info: LibraryToSlackChannel)(implicit session: RSession): Unit = {}
  override def invalidateCache(info: LibraryToSlackChannel)(implicit session: RSession): Unit = {}

  private def activeRows = rows.filter(row => row.state === LibraryToSlackChannelStates.ACTIVE)
  private def workingRows = activeRows.filter(row => row.status === (SlackIntegrationStatus.On: SlackIntegrationStatus))

  def getActiveByIds(ids: Set[Id[LibraryToSlackChannel]])(implicit session: RSession): Set[LibraryToSlackChannel] = {
    activeRows.filter(_.id.inSet(ids)).list.toSet
  }

  def getActiveByOwnerAndLibrary(ownerId: Id[User], libraryId: Id[Library])(implicit session: RSession): Set[LibraryToSlackChannel] = {
    activeRows.filter(row => row.ownerId === ownerId && row.libraryId === libraryId).list.toSet
  }

  def getBySlackTeamChannelAndLibrary(slackTeamId: SlackTeamId, slackChannelName: SlackChannelName, libraryId: Id[Library], excludeState: Option[State[LibraryToSlackChannel]] = Some(LibraryToSlackChannelStates.INACTIVE))(implicit session: RSession): Option[LibraryToSlackChannel] = {
    rows.filter(row => row.slackTeamId === slackTeamId && row.slackChannelName === slackChannelName && row.libraryId === libraryId && row.state =!= excludeState.orNull).firstOption
  }

  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationCreateRequest)(implicit session: RWSession): (LibraryToSlackChannel, Boolean) = {
    getBySlackTeamChannelAndLibrary(request.slackTeamId, request.slackChannelName, request.libraryId, excludeState = None) match {
      case Some(integration) if integration.isActive =>
        val isIntegrationOwner = integration.ownerId == request.userId && integration.slackUserId == request.slackUserId
        val updated = integration.copy(slackChannelName = request.slackChannelName)
        val saved = if (updated == integration) integration else save(updated)
        (saved, isIntegrationOwner)
      case inactiveIntegrationOpt =>
        val newIntegration = LibraryToSlackChannel(
          id = inactiveIntegrationOpt.map(_.id.get),
          ownerId = request.userId,
          space = request.space,
          slackUserId = request.slackUserId,
          slackTeamId = request.slackTeamId,
          slackChannelId = request.slackChannelId,
          slackChannelName = request.slackChannelName,
          libraryId = request.libraryId
        )
        (save(newIntegration), true)
    }
  }
  def deactivate(model: LibraryToSlackChannel)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }

  def getLibrariesRipeForProcessing(limit: Limit, overrideProcessesOlderThan: DateTime)(implicit session: RSession): Seq[Id[Library]] = {
    workingRows.filter(row => row.availableForProcessing(overrideProcessesOlderThan)).sortBy(_.lastProcessedAt).groupBy(_.libraryId).map(_._1).take(limit.value).list
  }
  def getIntegrationsRipeForProcessingByLibrary(libraryId: Id[Library], overrideProcessesOlderThan: DateTime)(implicit session: RWSession): Seq[Id[LibraryToSlackChannel]] = {
    workingRows.filter(row => row.libraryId === libraryId && row.availableForProcessing(overrideProcessesOlderThan)).map(_.id).list
  }
  def markAsProcessing(id: Id[LibraryToSlackChannel])(implicit session: RWSession): Option[LibraryToSlackChannel] = {
    val now = clock.now
    if (workingRows.filter(_.id === id).map(r => (r.updatedAt, r.lastProcessingAt)).update((now, Some(now))) > 0) {
      Some(workingRows.filter(_.id === id).first)
    } else None
  }
  def finishProcessing(model: LibraryToSlackChannel)(implicit session: RWSession): Unit = {
    save(model.finishedProcessing)
  }
}
