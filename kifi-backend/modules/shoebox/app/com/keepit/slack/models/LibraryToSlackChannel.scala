package com.keepit.slack.models

import javax.crypto.spec.IvParameterSpec

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.crypto.{ PublicIdGenerator, ModelWithPublicId }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.discussion.{ CrossServiceMessage, Message }
import com.keepit.model.LibrarySpace.{ OrganizationSpace, UserSpace }
import com.keepit.model._
import org.joda.time.{ Duration, Period, DateTime }
import com.keepit.common.core._

case class LibraryToSlackChannel(
  id: Option[Id[LibraryToSlackChannel]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[LibraryToSlackChannel] = LibraryToSlackChannelStates.ACTIVE,
  space: LibrarySpace,
  slackUserId: SlackUserId,
  slackTeamId: SlackTeamId,
  slackChannelId: Option[SlackChannelId],
  slackChannelName: SlackChannelName,
  libraryId: Id[Library],
  status: SlackIntegrationStatus = SlackIntegrationStatus.Off,
  lastProcessedAt: Option[DateTime] = None,
  lastProcessedKeep: Option[Id[KeepToLibrary]] = None,
  lastProcessedKeepSeq: Option[SequenceNumber[Keep]] = None,
  lastProcessedMsg: Option[Id[Message]] = None,
  lastProcessedMsgSeq: Option[SequenceNumber[Message]] = None,
  lastProcessingAt: Option[DateTime] = None,
  nextPushAt: Option[DateTime] = None)
    extends ModelWithState[LibraryToSlackChannel] with ModelWithPublicId[LibraryToSlackChannel] with ModelWithMaybeCopy[LibraryToSlackChannel] with SlackIntegration {
  def withId(id: Id[LibraryToSlackChannel]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive: Boolean = state == LibraryToSlackChannelStates.ACTIVE
  def withStatus(newStatus: SlackIntegrationStatus) = this.copy(status = newStatus, nextPushAt = if (newStatus == SlackIntegrationStatus.On) Some(currentDateTime) else None)
  def sanitizeForDelete = this.copy(state = LibraryToSlackChannelStates.INACTIVE, status = SlackIntegrationStatus.Off)
  def withLastProcessedAt(time: DateTime) = this.copy(lastProcessedAt = Some(time))
  def withNextPushAt(time: DateTime) = this.copy(status = SlackIntegrationStatus.On, nextPushAt = Some(time))
  def withNextPushAtLatest(time: DateTime) = nextPushAt match {
    case Some(t) if t < time => this
    case _ => this.withNextPushAt(time)
  }

  def finishedProcessing(lastKtl: Option[KeepToLibrary]) = this.copy(
    lastProcessedKeep = lastKtl.map(_.id.get) orElse lastProcessedKeep,
    lastProcessedAt = lastKtl.map(_.addedAt) orElse lastProcessingAt orElse lastProcessedAt,
    lastProcessingAt = None
  )

  def withModifications(mods: SlackIntegrationModification) = {
    this
      .maybeCopy(_.status, mods.status, _.withStatus)
      .maybeCopy(_.space, mods.space, _.withSpace)
  }

  def withSpace(newSpace: LibrarySpace) = this.copy(space = newSpace)

  def channel: (SlackChannelName, Option[SlackChannelId]) = (slackChannelName, slackChannelId)
}

object LibraryToSlackChannelStates extends States[LibraryToSlackChannel]
object LibraryToSlackChannel extends PublicIdGenerator[LibraryToSlackChannel] {
  protected val publicIdPrefix = "ltsc"
  protected val publicIdIvSpec = new IvParameterSpec(Array(-64, -39, 101, -61, 12, 125, 99, 20, -14, 28, -92, -120, 79, 50, -126, 18))
}

@ImplementedBy(classOf[LibraryToSlackChannelRepoImpl])
trait LibraryToSlackChannelRepo extends Repo[LibraryToSlackChannel] {
  def getByIds(ids: Set[Id[LibraryToSlackChannel]])(implicit session: RSession): Map[Id[LibraryToSlackChannel], LibraryToSlackChannel]
  def getActiveByIds(ids: Set[Id[LibraryToSlackChannel]])(implicit session: RSession): Set[LibraryToSlackChannel]
  def getActiveByLibrary(libraryId: Id[Library])(implicit session: RSession): Set[LibraryToSlackChannel]
  def getUserVisibleIntegrationsForLibraries(userId: Id[User], orgsForUser: Set[Id[Organization]], libraryIds: Set[Id[Library]])(implicit session: RSession): Seq[LibraryToSlackChannel]
  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationCreateRequest)(implicit session: RWSession): LibraryToSlackChannel

  def getIntegrationsByOrg(orgId: Id[Organization])(implicit session: RSession): Seq[LibraryToSlackChannel]
  def getBySlackTeam(teamId: SlackTeamId)(implicit session: RSession): Seq[LibraryToSlackChannel]

  def deactivate(model: LibraryToSlackChannel)(implicit session: RWSession): Unit

  def getRipeForPushing(limit: Int, pushingForMoreThan: Duration)(implicit session: RSession): Seq[Id[LibraryToSlackChannel]]
  def getRipeForPushingViaNewActor(limit: Int, pushingForMoreThan: Duration)(implicit session: RSession): Seq[Id[LibraryToSlackChannel]]
  def markAsPushing(id: Id[LibraryToSlackChannel], pushingForMoreThan: Duration)(implicit session: RWSession): Boolean
  def updateLastProcessedKeep(id: Id[LibraryToSlackChannel], lastProcessedKeep: Id[KeepToLibrary])(implicit session: RWSession): Unit
  def updateLastProcessedMsg(id: Id[LibraryToSlackChannel], lastProcessedMsg: Id[Message])(implicit session: RWSession): Unit
  def updateLastProcessedSeqs(id: Id[LibraryToSlackChannel], keepSeqOpt: Option[SequenceNumber[Keep]], msgSeqOpt: Option[SequenceNumber[Message]])(implicit session: RWSession): Unit
  def updateAfterPush(id: Id[LibraryToSlackChannel], nextPushAt: Option[DateTime], status: SlackIntegrationStatus)(implicit session: RWSession): Unit

  def getBySlackTeamAndChannel(teamId: SlackTeamId, channelId: SlackChannelId)(implicit session: RSession): Seq[LibraryToSlackChannel]
  def getWithMissingChannelId()(implicit session: RSession): Set[(SlackUserId, SlackTeamId, SlackChannelName)]
  def fillInMissingChannelId(userId: SlackUserId, teamId: SlackTeamId, channelName: SlackChannelName, channelId: SlackChannelId)(implicit session: RWSession): Int
}

@Singleton
class LibraryToSlackChannelRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    integrationsCache: SlackChannelIntegrationsCache) extends DbRepo[LibraryToSlackChannel] with LibraryToSlackChannelRepo {

  import com.keepit.common.db.slick.DBSession._
  import db.Driver.simple._

  implicit val slackUserIdColumnType = SlackDbColumnTypes.userId(db)
  implicit val slackTeamIdColumnType = SlackDbColumnTypes.teamId(db)
  implicit val slackChannelIdColumnType = SlackDbColumnTypes.channelId(db)
  implicit val slackChannelColumnType = SlackDbColumnTypes.channelName(db)
  implicit val statusColumnType = SlackIntegrationStatus.columnType(db)
  implicit val msgIdColumnType = MappedColumnType.base[Id[Message], Long](id => id.id, v => Id(v))

  private def ltsFromDbRow(
    id: Option[Id[LibraryToSlackChannel]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[LibraryToSlackChannel],
    userId: Option[Id[User]],
    organizationId: Option[Id[Organization]],
    slackUserId: SlackUserId,
    slackTeamId: SlackTeamId,
    slackChannelId: Option[SlackChannelId],
    slackChannelName: SlackChannelName,
    libraryId: Id[Library],
    status: SlackIntegrationStatus,
    lastProcessedAt: Option[DateTime],
    lastProcessedKeep: Option[Id[KeepToLibrary]],
    lastProcessedKeepSeq: Option[SequenceNumber[Keep]],
    lastProcessedMsg: Option[Id[Message]],
    lastProcessedMsgSeq: Option[SequenceNumber[Message]],
    startedProcessingAt: Option[DateTime],
    nextPushAt: Option[DateTime]) = {
    LibraryToSlackChannel(
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
      lastProcessedAt = lastProcessedAt,
      lastProcessedKeep = lastProcessedKeep,
      lastProcessedKeepSeq = lastProcessedKeepSeq,
      lastProcessedMsg = lastProcessedMsg,
      lastProcessedMsgSeq = lastProcessedMsgSeq,
      lastProcessingAt = startedProcessingAt,
      nextPushAt = nextPushAt
    )
  }

  private def ltsToDbRow(lts: LibraryToSlackChannel) = Some((
    lts.id,
    lts.createdAt,
    lts.updatedAt,
    lts.state,
    Some(lts.space).collect { case UserSpace(userId) => userId },
    Some(lts.space).collect { case OrganizationSpace(orgId) => orgId },
    lts.slackUserId,
    lts.slackTeamId,
    lts.slackChannelId,
    lts.slackChannelName,
    lts.libraryId,
    lts.status,
    lts.lastProcessedAt,
    lts.lastProcessedKeep,
    lts.lastProcessedKeepSeq,
    lts.lastProcessedMsg,
    lts.lastProcessedMsgSeq,
    lts.lastProcessingAt,
    lts.nextPushAt
  ))

  type RepoImpl = LibraryToSlackChannelTable

  class LibraryToSlackChannelTable(tag: Tag) extends RepoTable[LibraryToSlackChannel](db, tag, "library_to_slack_channel") {
    def userId = column[Option[Id[User]]]("owner_id", O.Nullable) // TODO(ryan): change to "user_id"
    def organizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)
    def slackUserId = column[SlackUserId]("slack_user_id", O.NotNull)
    def slackTeamId = column[SlackTeamId]("slack_team_id", O.NotNull)
    def slackChannelId = column[Option[SlackChannelId]]("slack_channel_id", O.Nullable)
    def slackChannelName = column[SlackChannelName]("slack_channel_name", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def status = column[SlackIntegrationStatus]("status", O.NotNull)
    def lastProcessedAt = column[Option[DateTime]]("last_processed_at", O.Nullable)
    def lastProcessedKeep = column[Option[Id[KeepToLibrary]]]("last_processed_ktl", O.Nullable)
    def lastProcessedKeepSeq = column[Option[SequenceNumber[Keep]]]("last_processed_keep_seq", O.Nullable)
    def lastProcessedMsg = column[Option[Id[Message]]]("last_processed_msg", O.Nullable)
    def lastProcessedMsgSeq = column[Option[SequenceNumber[Message]]]("last_processed_msg_seq", O.Nullable)
    def lastProcessingAt = column[Option[DateTime]]("last_processing_at", O.Nullable)
    def nextPushAt = column[Option[DateTime]]("next_push_at", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, userId, organizationId, slackUserId, slackTeamId, slackChannelId, slackChannelName, libraryId, status, lastProcessedAt, lastProcessedKeep, lastProcessedKeepSeq, lastProcessedMsg, lastProcessedMsgSeq, lastProcessingAt, nextPushAt) <> ((ltsFromDbRow _).tupled, ltsToDbRow _)

    def availableForProcessing(overrideDate: DateTime) = lastProcessingAt.isEmpty || lastProcessingAt < overrideDate
  }

  def table(tag: Tag) = new LibraryToSlackChannelTable(tag)
  initTable()
  override def deleteCache(info: LibraryToSlackChannel)(implicit session: RSession): Unit = {
    info.slackChannelId.foreach(channelId => integrationsCache.remove(SlackChannelIntegrationsKey(info.slackTeamId, channelId)))
  }
  override def invalidateCache(info: LibraryToSlackChannel)(implicit session: RSession): Unit = deleteCache(info)

  private def activeRows = rows.filter(row => row.state === LibraryToSlackChannelStates.ACTIVE)
  private def workingRows = activeRows.filter(row => row.status === (SlackIntegrationStatus.On: SlackIntegrationStatus))

  def getByIds(ids: Set[Id[LibraryToSlackChannel]])(implicit session: RSession): Map[Id[LibraryToSlackChannel], LibraryToSlackChannel] = {
    rows.filter(row => row.id.inSet(ids)).list.map(r => (r.id.get, r)).toMap
  }

  def getActiveByIds(ids: Set[Id[LibraryToSlackChannel]])(implicit session: RSession): Set[LibraryToSlackChannel] = {
    activeRows.filter(_.id.inSet(ids)).list.toSet
  }
  def getActiveByLibrary(libraryId: Id[Library])(implicit session: RSession): Set[LibraryToSlackChannel] = {
    activeRows.filter(_.libraryId === libraryId).list.toSet
  }
  def getUserVisibleIntegrationsForLibraries(userId: Id[User], orgsForUser: Set[Id[Organization]], libraryIds: Set[Id[Library]])(implicit session: RSession): Seq[LibraryToSlackChannel] = {
    // TODO(ryan): should be able to drop the `&& row.organizationId.isEmpty`
    activeRows.filter(row => row.libraryId.inSet(libraryIds) && row.organizationId.inSet(orgsForUser) || (row.userId === userId && row.organizationId.isEmpty)).list
  }

  def getBySlackTeamChannelAndLibrary(request: SlackIntegrationCreateRequest, excludeState: Option[State[LibraryToSlackChannel]] = Some(LibraryToSlackChannelStates.INACTIVE))(implicit session: RSession): Option[LibraryToSlackChannel] = {
    rows.filter(row => row.slackTeamId === request.slackTeamId && (row.slackChannelId === request.slackChannelId || (row.slackUserId === request.slackUserId && row.slackChannelName === request.slackChannelName)) && row.libraryId === request.libraryId && row.state =!= excludeState.orNull).firstOption
  }
  def getIntegrationsByOrg(orgId: Id[Organization])(implicit session: RSession): Seq[LibraryToSlackChannel] = {
    activeRows.filter(row => row.organizationId === orgId).list
  }

  def getBySlackTeam(teamId: SlackTeamId)(implicit session: RSession): Seq[LibraryToSlackChannel] = {
    activeRows.filter(row => row.slackTeamId === teamId).list
  }

  def internBySlackTeamChannelAndLibrary(request: SlackIntegrationCreateRequest)(implicit session: RWSession): LibraryToSlackChannel = {
    val now = clock.now
    getBySlackTeamChannelAndLibrary(request, excludeState = None) match {
      case Some(integration) if integration.isActive =>
        val updatedStatus = if (integration.status == SlackIntegrationStatus.On) integration.status else request.status
        val updated = integration.copy(
          space = request.space,
          slackUserId = request.slackUserId,
          slackChannelName = request.slackChannelName,
          slackChannelId = request.slackChannelId
        ).withStatus(updatedStatus)
        val saved = if (updated == integration) integration else save(updated)
        saved
      case inactiveIntegrationOpt =>
        val newIntegration = LibraryToSlackChannel(
          id = inactiveIntegrationOpt.map(_.id.get),
          space = request.space,
          slackUserId = request.slackUserId,
          slackTeamId = request.slackTeamId,
          slackChannelId = request.slackChannelId,
          slackChannelName = request.slackChannelName,
          libraryId = request.libraryId
        ).withStatus(request.status)
        save(newIntegration)
    }
  }
  def deactivate(model: LibraryToSlackChannel)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }

  def getRipeForPushing(limit: Int, pushingForMoreThan: Duration)(implicit session: RSession): Seq[Id[LibraryToSlackChannel]] = {
    val now = clock.now
    val lastPushingTooLongAgo = now minus pushingForMoreThan
    workingRows.filter(row => row.slackTeamId =!= KifiSlackApp.BrewstercorpTeamId && row.nextPushAt <= now && row.availableForProcessing(lastPushingTooLongAgo)).sortBy(row => (row.lastProcessedAt.isDefined, row.nextPushAt)).map(_.id).take(limit).list
  }
  // TODO(ryan): once the new actor works, unify these two methods. They differ only in which slack teams are chosen
  def getRipeForPushingViaNewActor(limit: Int, pushingForMoreThan: Duration)(implicit session: RSession): Seq[Id[LibraryToSlackChannel]] = {
    val now = clock.now
    val lastPushingTooLongAgo = now minus pushingForMoreThan
    workingRows.filter(row => row.slackTeamId === KifiSlackApp.BrewstercorpTeamId && row.nextPushAt <= now && row.availableForProcessing(lastPushingTooLongAgo)).sortBy(row => (row.lastProcessedAt.isDefined, row.nextPushAt)).map(_.id).take(limit).list
  }

  def markAsPushing(id: Id[LibraryToSlackChannel], pushingForMoreThan: Duration)(implicit session: RWSession): Boolean = {
    val now = clock.now
    val lastPushingTooLongAgo = now minus pushingForMoreThan
    workingRows.filter(row => row.id === id && row.availableForProcessing(lastPushingTooLongAgo)).map(r => (r.updatedAt, r.lastProcessingAt)).update((now, Some(now))) > 0
  }

  def updateLastProcessedKeep(id: Id[LibraryToSlackChannel], lastProcessedKeep: Id[KeepToLibrary])(implicit session: RWSession): Unit = {
    val now = clock.now
    rows.filter(_.id === id).map(r => (r.updatedAt, r.lastProcessedKeep)).update((now, Some(lastProcessedKeep)))
  }
  def updateLastProcessedMsg(id: Id[LibraryToSlackChannel], lastProcessedMsg: Id[Message])(implicit session: RWSession): Unit = {
    val now = clock.now
    rows.filter(_.id === id).map(r => (r.updatedAt, r.lastProcessedMsg)).update((now, Some(lastProcessedMsg)))
  }
  def updateLastProcessedSeqs(id: Id[LibraryToSlackChannel], keepSeqOpt: Option[SequenceNumber[Keep]], msgSeqOpt: Option[SequenceNumber[Message]])(implicit session: RWSession): Unit = {
    val now = clock.now
    keepSeqOpt.foreach { keepSeq =>
      rows.filter(r => r.id === id && (r.lastProcessedKeepSeq.isEmpty || r.lastProcessedKeepSeq < keepSeq)).map(r => (r.updatedAt, r.lastProcessedKeepSeq)).update((now, Some(keepSeq)))
    }
    msgSeqOpt.foreach { msgSeq =>
      rows.filter(r => r.id === id && (r.lastProcessedMsgSeq.isEmpty || r.lastProcessedMsgSeq < msgSeq)).map(r => (r.updatedAt, r.lastProcessedMsgSeq)).update((now, Some(msgSeq)))
    }
  }

  def updateAfterPush(id: Id[LibraryToSlackChannel], nextPushAt: Option[DateTime], status: SlackIntegrationStatus)(implicit session: RWSession): Unit = {
    val now = clock.now()
    (for (r <- rows if r.id === id) yield (r.updatedAt, r.lastProcessingAt, r.lastProcessedAt, r.nextPushAt, r.status)).update((now, None, Some(now), nextPushAt, status))
  }

  def getBySlackTeamAndChannel(teamId: SlackTeamId, channelId: SlackChannelId)(implicit session: RSession): Seq[LibraryToSlackChannel] = {
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
}
