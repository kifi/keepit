package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock

@ImplementedBy(classOf[KeepToLibraryRepoImpl])
trait KeepToLibraryRepo extends Repo[KeepToLibrary] {
  def countByKeepId(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToLibrary]] = Some(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Int
  def getAllByKeepId(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToLibrary]] = Some(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary]

  def countByLibraryId(libraryId: Id[Library], excludeStateOpt: Option[State[KeepToLibrary]] = Some(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Int
  def getByLibraryId(libraryId: Id[Library], limit: Limit, offset: Offset, excludeStateOpt: Option[State[KeepToLibrary]] = Some(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary]
  def getAllByLibraryId(libraryId: Id[Library], excludeStateOpt: Option[State[KeepToLibrary]] = Some(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary]

  def getByKeepIdAndLibraryId(keepId: Id[Keep], libraryId: Id[Library], excludeStateOpt: Option[State[KeepToLibrary]] = Some(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Option[KeepToLibrary]

  def deactivate(model: KeepToLibrary)(implicit session: RWSession): Unit
}

@Singleton
class KeepToLibraryRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends KeepToLibraryRepo with DbRepo[KeepToLibrary] with Logging {

  override def deleteCache(orgMember: KeepToLibrary)(implicit session: RSession) {}
  override def invalidateCache(orgMember: KeepToLibrary)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = KeepToLibraryTable
  class KeepToLibraryTable(tag: Tag) extends RepoTable[KeepToLibrary](db, tag, "keep_to_library") {
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def keeperId = column[Id[User]]("keeper_id", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, keepId, libraryId, keeperId) <> ((KeepToLibrary.apply _).tupled, KeepToLibrary.unapply)
  }

  def table(tag: Tag) = new KeepToLibraryTable(tag)
  initTable()

  private def getByKeepIdHelper(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToLibrary]])(implicit session: RSession) = {
    for (row <- rows if row.keepId === keepId && row.state =!= excludeStateOpt.orNull) yield row
  }
  def countByKeepId(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToLibrary]] = Some(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Int = {
    getByKeepIdHelper(keepId, excludeStateOpt).run.length
  }
  def getAllByKeepId(keepId: Id[Keep], excludeStateOpt: Option[State[KeepToLibrary]] = Some(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary] = {
    getByKeepIdHelper(keepId, excludeStateOpt).list
  }

  private def getByLibraryIdHelper(libraryId: Id[Library], excludeStateOpt: Option[State[KeepToLibrary]])(implicit session: RSession) = {
    for (row <- rows if row.libraryId === libraryId && row.state =!= excludeStateOpt.orNull) yield row
  }
  def countByLibraryId(libraryId: Id[Library], excludeStateOpt: Option[State[KeepToLibrary]] = Some(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Int = {
    getByLibraryIdHelper(libraryId, excludeStateOpt).run.length
  }
  def getByLibraryId(libraryId: Id[Library], limit: Limit, offset: Offset, excludeStateOpt: Option[State[KeepToLibrary]] = Some(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary] = {
    getByLibraryIdHelper(libraryId, excludeStateOpt).drop(offset.value).take(limit.value).list
  }
  def getAllByLibraryId(libraryId: Id[Library], excludeStateOpt: Option[State[KeepToLibrary]] = Some(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary] = {
    getByLibraryIdHelper(libraryId, excludeStateOpt).list
  }

  private def getByKeepIdAndLibraryIdHelper(keepId: Id[Keep], libraryId: Id[Library], excludeStateOpt: Option[State[KeepToLibrary]])(implicit session: RSession) = {
    for (row <- rows if row.keepId === keepId && row.libraryId === libraryId && row.state =!= excludeStateOpt.orNull) yield row
  }
  def getByKeepIdAndLibraryId(keepId: Id[Keep], libraryId: Id[Library], excludeStateOpt: Option[State[KeepToLibrary]] = Some(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Option[KeepToLibrary] = {
    getByKeepIdAndLibraryIdHelper(keepId, libraryId, excludeStateOpt).firstOption
  }

  def deactivate(model: KeepToLibrary)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}
