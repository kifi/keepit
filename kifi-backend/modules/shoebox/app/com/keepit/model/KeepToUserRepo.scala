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
  def countByKeepId(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToUser]] = Some(KeepToUserStates.INACTIVE))(implicit session: RSession): Int
  def getAllByKeepId(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToUser]] = Some(KeepToUserStates.INACTIVE))(implicit session: RSession): Seq[KeepToUser]

  def getCountByUserId(userId: Id[User], excludeStateOpt: Option[State[KeepToUser]] = Some(KeepToUserStates.INACTIVE))(implicit session: RSession): Int
  def getCountsByUserIds(userIds: Set[Id[User]], excludeStateOpt: Option[State[KeepToUser]] = Some(KeepToUserStates.INACTIVE))(implicit session: RSession): Map[Id[User], Int]
  def getAllByUserId(userId: Id[User], excludeStateOpt: Option[State[KeepToUser]] = Some(KeepToUserStates.INACTIVE))(implicit session: RSession): Seq[KeepToUser]
  def getAllByUserIds(userIds: Set[Id[User]], excludeStateOpt: Option[State[KeepToUser]] = Some(KeepToUserStates.INACTIVE))(implicit session: RSession): Map[Id[User], Seq[KeepToUser]]

  def getByKeepIdAndUserId(keepId: Id[Keep], userId: Id[User], excludeStateOpt: Option[State[KeepToUser]] = Some(KeepToUserStates.INACTIVE))(implicit session: RSession): Option[KeepToUser]

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
    def addedBy = column[Id[User]]("added_by", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, keepId, userId, addedAt, addedBy, uriId) <> ((KeepToUser.applyFromDbRow _).tupled, KeepToUser.unapplyToDbRow)
  }

  def table(tag: Tag) = new KeepToUserTable(tag)
  initTable()

  private def getByKeepIdHelper(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToUser]])(implicit session: RSession) = {
    for (row <- rows if row.keepId === keepId && row.state =!= excludeStateOpt.orNull) yield row
  }
  def countByKeepId(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToUser]] = Option(KeepToUserStates.INACTIVE))(implicit session: RSession): Int = {
    getByKeepIdHelper(keepId, excludeStateOpt).run.length
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
  def getCountByUserId(userId: Id[User], excludeStateOpt: Option[State[KeepToUser]] = Option(KeepToUserStates.INACTIVE))(implicit session: RSession): Int = {
    getByUserIdHelper(userId, excludeStateOpt).run.length
  }
  def getCountsByUserIds(userIds: Set[Id[User]], excludeStateOpt: Option[State[KeepToUser]] = Option(KeepToUserStates.INACTIVE))(implicit session: RSession): Map[Id[User], Int] = {
    // TODO(ryan): This needs to use a cache, and fall back on a single monster query, not a bunch of tiny queries
    userIds.map(libId => libId -> getCountByUserId(libId, excludeStateOpt)).toMap
  }
  def getAllByUserId(userId: Id[User], excludeStateOpt: Option[State[KeepToUser]] = Option(KeepToUserStates.INACTIVE))(implicit session: RSession): Seq[KeepToUser] = {
    getByUserIdHelper(userId, excludeStateOpt).list
  }
  def getAllByUserIds(userIds: Set[Id[User]], excludeStateOpt: Option[State[KeepToUser]] = Option(KeepToUserStates.INACTIVE))(implicit session: RSession): Map[Id[User], Seq[KeepToUser]] = {
    getByUserIdsHelper(userIds, excludeStateOpt).list.groupBy(_.userId)
  }

  private def getByKeepIdAndUserIdHelper(keepId: Id[Keep], userId: Id[User], excludeStateOpt: Option[State[KeepToUser]])(implicit session: RSession) = {
    for (row <- rows if row.keepId === keepId && row.userId === userId && row.state =!= excludeStateOpt.orNull) yield row
  }
  def getByKeepIdAndUserId(keepId: Id[Keep], userId: Id[User], excludeStateOpt: Option[State[KeepToUser]] = Option(KeepToUserStates.INACTIVE))(implicit session: RSession): Option[KeepToUser] = {
    getByKeepIdAndUserIdHelper(keepId, userId, excludeStateOpt).firstOption
  }

  def deactivate(model: KeepToUser)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}
