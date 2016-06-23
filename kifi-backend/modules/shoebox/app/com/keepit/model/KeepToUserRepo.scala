package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[KeepToUserRepoImpl])
trait KeepToUserRepo extends Repo[KeepToUser] {
  def getAllByKeepId(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToUser]] = Some(KeepToUserStates.INACTIVE))(implicit session: RSession): Seq[KeepToUser]
  def getAllByKeepIds(keepIds: Set[Id[Keep]], excludeState: Option[State[KeepToUser]] = Some(KeepToUserStates.INACTIVE))(implicit session: RSession): Map[Id[Keep], Seq[KeepToUser]]
  def getAllByUserId(userId: Id[User], excludeStateOpt: Option[State[KeepToUser]] = Some(KeepToUserStates.INACTIVE))(implicit session: RSession): Seq[KeepToUser]
  def getByKeepIdAndUserId(keepId: Id[Keep], userId: Id[User], excludeStateOpt: Option[State[KeepToUser]] = Some(KeepToUserStates.INACTIVE))(implicit session: RSession): Option[KeepToUser]
  def getByUserIdAndUriIds(userId: Id[User], uriIds: Set[Id[NormalizedURI]], excludeStateOpt: Option[State[KeepToUser]] = Some(KeepToUserStates.INACTIVE))(implicit session: RSession): Set[KeepToUser]
  def pageByUserId(userId: Id[User], offset: Int, limit: Int)(implicit session: RSession): Seq[KeepToUser]
  def deactivate(model: KeepToUser)(implicit session: RWSession): Unit
}

@Singleton
class KeepToUserRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends KeepToUserRepo with DbRepo[KeepToUser] with Logging {

  override def deleteCache(ktu: KeepToUser)(implicit session: RSession) {}
  override def invalidateCache(ktu: KeepToUser)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = KeepToUserTable
  class KeepToUserTable(tag: Tag) extends RepoTable[KeepToUser](db, tag, "keep_to_user") {
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def addedAt = column[DateTime]("added_at", O.NotNull)
    def addedBy = column[Option[Id[User]]]("added_by", O.Nullable)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def lastActivityAt = column[DateTime]("last_activity_at", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, keepId, userId, addedAt, addedBy, uriId, lastActivityAt) <> ((fromDbRow _).tupled, toDbRow)
  }

  private def fromDbRow(id: Option[Id[KeepToUser]], createdAt: DateTime, updatedAt: DateTime, state: State[KeepToUser],
    keepId: Id[Keep], userId: Id[User], addedAt: DateTime, addedBy: Option[Id[User]],
    uriId: Id[NormalizedURI], lastActivityAt: DateTime): KeepToUser = {
    KeepToUser(
      id, createdAt, updatedAt, state,
      keepId, userId, addedAt, addedBy,
      uriId, lastActivityAt)
  }

  private def toDbRow(ktu: KeepToUser) = {
    Some(
      (ktu.id, ktu.createdAt, ktu.updatedAt, ktu.state,
        ktu.keepId, ktu.userId, ktu.addedAt, ktu.addedBy,
        ktu.uriId, ktu.lastActivityAt)
    )
  }

  def table(tag: Tag) = new KeepToUserTable(tag)
  initTable()

  def activeRows = rows.filter(_.state === KeepToUserStates.ACTIVE)

  private def getByKeepIdHelper(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToUser]])(implicit session: RSession) = {
    for (row <- rows if row.keepId === keepId && row.state =!= excludeStateOpt.orNull) yield row
  }

  private def getByKeepIdsHelper(keepIds: Set[Id[Keep]], excludeState: Option[State[KeepToUser]])(implicit session: RSession) = rows.filter(r => r.keepId.inSet(keepIds) && r.state =!= excludeState.orNull)
  def getAllByKeepIds(keepIds: Set[Id[Keep]], excludeState: Option[State[KeepToUser]])(implicit session: RSession): Map[Id[Keep], Seq[KeepToUser]] = {
    getByKeepIdsHelper(keepIds, excludeState).list.groupBy(_.keepId)
  }

  def getAllByKeepId(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToUser]] = Option(KeepToUserStates.INACTIVE))(implicit session: RSession): Seq[KeepToUser] = {
    getByKeepIdHelper(keepId, excludeStateOpt).list
  }

  private def getByUserIdsHelper(userIds: Set[Id[User]], excludeStateOpt: Option[State[KeepToUser]])(implicit session: RSession) = {
    for (row <- rows if row.userId.inSet(userIds) && row.state =!= excludeStateOpt.orNull) yield row
  }
  private def getByUserIdHelper(userId: Id[User], excludeStateOpt: Option[State[KeepToUser]])(implicit session: RSession) = {
    getByUserIdsHelper(Set(userId), excludeStateOpt)
  }
  def getAllByUserId(userId: Id[User], excludeStateOpt: Option[State[KeepToUser]] = Option(KeepToUserStates.INACTIVE))(implicit session: RSession): Seq[KeepToUser] = {
    getByUserIdHelper(userId, excludeStateOpt).list
  }

  private def getByKeepIdAndUserIdHelper(keepId: Id[Keep], userId: Id[User], excludeStateOpt: Option[State[KeepToUser]])(implicit session: RSession) = {
    for (row <- rows if row.keepId === keepId && row.userId === userId && row.state =!= excludeStateOpt.orNull) yield row
  }
  def getByKeepIdAndUserId(keepId: Id[Keep], userId: Id[User], excludeStateOpt: Option[State[KeepToUser]] = Option(KeepToUserStates.INACTIVE))(implicit session: RSession): Option[KeepToUser] = {
    getByKeepIdAndUserIdHelper(keepId, userId, excludeStateOpt).firstOption
  }

  private def getByUserIdsAndUriIdsHelper(userIds: Set[Id[User]], uriIds: Set[Id[NormalizedURI]], excludeStateOpt: Option[State[KeepToUser]])(implicit session: RSession) = {
    for (row <- rows if row.userId.inSet(userIds) && row.uriId.inSet(uriIds) && row.state =!= excludeStateOpt.orNull) yield row
  }
  def getByUserIdAndUriIds(userId: Id[User], uriIds: Set[Id[NormalizedURI]], excludeStateOpt: Option[State[KeepToUser]] = Some(KeepToUserStates.INACTIVE))(implicit session: RSession): Set[KeepToUser] = {
    getByUserIdsAndUriIdsHelper(Set(userId), uriIds, excludeStateOpt).list.toSet
  }
  def pageByUserId(userId: Id[User], offset: Int, limit: Int)(implicit session: RSession): Seq[KeepToUser] = {
    activeRows.filter(_.userId === userId).sortBy(_.id).drop(offset).take(limit).list
  }

  def deactivate(model: KeepToUser)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}
