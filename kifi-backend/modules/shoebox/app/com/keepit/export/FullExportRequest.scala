package com.keepit.export

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

final case class FullExportRequest(
  id: Option[Id[FullExportRequest]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[FullExportRequest] = FullExportRequestStates.ACTIVE,
  userId: Id[User],
  status: FullExportStatus)
    extends ModelWithState[FullExportRequest] {
  def withId(id: Id[FullExportRequest]): FullExportRequest = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): FullExportRequest = this.copy(updatedAt = now)
  def withState(newState: State[FullExportRequest]): FullExportRequest = this.copy(state = newState)
  def withStatus(newStatus: FullExportStatus) = this.copy(status = newStatus)

  def isActive = state == FullExportRequestStates.ACTIVE
  def isInactive = state == FullExportRequestStates.INACTIVE

  def sanitizeForDelete = this.withState(FullExportRequestStates.INACTIVE)
}
object FullExportRequestStates extends States[FullExportRequest]

sealed abstract class FullExportStatus
object FullExportStatus {
  case object NotStarted extends FullExportStatus
  case class InProgress(startedAt: DateTime) extends FullExportStatus
  case class Failed(startedAt: DateTime, failedAt: DateTime, message: String) extends FullExportStatus
  case class Finished(startedAt: DateTime, finishedAt: DateTime, uploadLocation: String) extends FullExportStatus

  def finishedAtPrettyString(status: FullExportStatus.Finished): String = {
    val format = DateTimeFormat.forPattern("d/M/yyyy 'at' h:mm")
    val finishedAt = status.finishedAt.toDateTime(zones.PT)
    val isAm = finishedAt.hourOfDay().get < 12
    s"${finishedAt.toString(format)} ${if (isAm) "AM" else "PM"} PDT"
  }
}

@ImplementedBy(classOf[FullExportRequestRepoImpl])
trait FullExportRequestRepo extends Repo[FullExportRequest] {
  def intern(model: FullExportRequest)(implicit session: RWSession): FullExportRequest
  def getByUser(userId: Id[User])(implicit session: RSession): Option[FullExportRequest]

  def getRipeIds(limit: Int, overrideProcessesOlderThan: DateTime)(implicit session: RSession): Seq[Id[FullExportRequest]]
  def markAsProcessing(id: Id[FullExportRequest], overrideProcessesOlderThan: DateTime)(implicit session: RWSession): Boolean
  def markAsComplete(id: Id[FullExportRequest], uploadLocation: String)(implicit session: RWSession): Unit
  def markAsFailed(id: Id[FullExportRequest], failure: String)(implicit session: RWSession): Unit
}

@Singleton
class FullExportRequestRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends FullExportRequestRepo with DbRepo[FullExportRequest] with Logging {

  override def deleteCache(ktu: FullExportRequest)(implicit session: RSession) {}
  override def invalidateCache(ktu: FullExportRequest)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = FullExportRequestTable
  class FullExportRequestTable(tag: Tag) extends RepoTable[FullExportRequest](db, tag, "export_request") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def startedProcessingAt = column[Option[DateTime]]("started_processing_at", O.Nullable)
    def finishedProcessingAt = column[Option[DateTime]]("finished_processing_at", O.Nullable)
    def failureMessage = column[Option[String]]("failure_message", O.Nullable)
    def uploadLocation = column[Option[String]]("upload_location", O.Nullable)

    def * = (
      id.?, createdAt, updatedAt, state,
      userId, startedProcessingAt, finishedProcessingAt, failureMessage, uploadLocation
    ) <> ((fromDbRow _).tupled, toDbRow)

    def availableForProcessing(threshold: DateTime) = {
      startedProcessingAt.isEmpty ||
        (startedProcessingAt < threshold && (
          finishedProcessingAt.isEmpty || failureMessage.isDefined
        ))
    }

  }

  private def fromDbRow(id: Option[Id[FullExportRequest]], createdAt: DateTime, updatedAt: DateTime, state: State[FullExportRequest],
    userId: Id[User], startedProcessingAt: Option[DateTime], finishedProcessingAt: Option[DateTime], failureMessage: Option[String], uploadLocation: Option[String]) = {

    val status: FullExportStatus = (startedProcessingAt, finishedProcessingAt, failureMessage, uploadLocation) match {
      case (None, _, _, _) =>
        FullExportStatus.NotStarted
      case (Some(start), None, _, _) =>
        FullExportStatus.InProgress(start)
      case (Some(start), Some(finish), Some(fail), _) =>
        FullExportStatus.Failed(start, finish, fail)
      case (Some(start), Some(finish), _, Some(uploaded)) =>
        FullExportStatus.Finished(start, finish, uploaded)
      case badStatus =>
        throw new IllegalArgumentException(s"Unknown status combination: $badStatus")
    }
    FullExportRequest(id, createdAt, updatedAt, state, userId, status)
  }

  private def toDbRow(req: FullExportRequest) = {
    val (startedAt, finishedAt, failMessage, uploadLocation) = req.status match {
      case FullExportStatus.NotStarted =>
        (None, None, None, None)
      case FullExportStatus.InProgress(start) =>
        (Some(start), None, None, None)
      case FullExportStatus.Failed(start, end, fail) =>
        (Some(start), Some(end), Some(fail), None)
      case FullExportStatus.Finished(start, end, upload) =>
        (Some(start), Some(end), None, Some(upload))
    }
    Some((req.id, req.createdAt, req.updatedAt, req.state, req.userId, startedAt, finishedAt, failMessage, uploadLocation))
  }

  def table(tag: Tag) = new FullExportRequestTable(tag)
  initTable()

  def activeRows = rows.filter(_.state === FullExportRequestStates.ACTIVE)
  def deadRows = rows.filter(_.state === FullExportRequestStates.INACTIVE)

  def intern(model: FullExportRequest)(implicit session: RWSession): FullExportRequest = {
    if (model.id.isDefined) save(model)
    else {
      val obstacle = rows.filter(_.userId === model.userId).firstOption
      save(model.copy(id = obstacle.map(_.id.get)))
    }
  }

  def getByUser(userId: Id[User])(implicit session: RSession): Option[FullExportRequest] = {
    activeRows.filter(_.userId === userId).firstOption
  }

  def getRipeIds(limit: Int, overrideProcessesOlderThan: DateTime)(implicit session: RSession): Seq[Id[FullExportRequest]] = {
    activeRows
      .filter(row => row.availableForProcessing(overrideProcessesOlderThan))
      .sortBy(_.id).map(_.id).take(limit).list
  }

  def markAsProcessing(id: Id[FullExportRequest], overrideProcessesOlderThan: DateTime)(implicit session: RWSession): Boolean = {
    val now = clock.now
    activeRows
      .filter(row => row.id === id && row.availableForProcessing(overrideProcessesOlderThan))
      .map(r => (r.updatedAt, r.startedProcessingAt))
      .update((now, Some(now))) > 0
  }

  def markAsFailed(id: Id[FullExportRequest], failure: String)(implicit session: RWSession): Unit = {
    val now = clock.now
    activeRows
      .filter(_.id === id)
      .map(r => (r.updatedAt, r.finishedProcessingAt, r.failureMessage))
      .update((now, Some(now), Some(failure)))
  }

  def markAsComplete(id: Id[FullExportRequest], uploadLocation: String)(implicit session: RWSession): Unit = {
    // Must be called on a row that has been marked as processing (see markAsProcessing)
    val now = clock.now
    activeRows
      .filter(_.id === id)
      .map(r => (r.updatedAt, r.finishedProcessingAt, r.uploadLocation))
      .update((now, Some(now), Some(uploadLocation)))
  }
}
