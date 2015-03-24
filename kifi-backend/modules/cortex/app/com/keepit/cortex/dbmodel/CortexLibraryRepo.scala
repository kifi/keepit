package com.keepit.cortex.dbmodel

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ SeqNumberDbFunction, DataBaseComponent, SeqNumberFunction, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.model.{ LibraryKind, User, Library }

import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[CortexLibraryRepoImpl])
trait CortexLibraryRepo extends DbRepo[CortexLibrary] with SeqNumberFunction[CortexLibrary] {
  def getSince(seq: SequenceNumber[CortexLibrary], limit: Int)(implicit session: RSession): Seq[CortexLibrary]
  def getMaxSeq()(implicit session: RSession): SequenceNumber[CortexLibrary]
  def getByLibraryId(libId: Id[Library])(implicit session: RSession): Option[CortexLibrary]
}

@Singleton
class CortexLibraryRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[CortexLibrary] with CortexLibraryRepo with SeqNumberDbFunction[CortexLibrary] {

  import db.Driver.simple._

  type RepoImpl = CortexLibraryTable

  class CortexLibraryTable(tag: Tag) extends RepoTable[CortexLibrary](db, tag, "cortex_library") with SeqNumberColumn[CortexLibrary] {
    def libraryId = column[Id[Library]]("library_id")
    def ownerId = column[Id[User]]("owner_id")
    def kind = column[LibraryKind]("kind", O.NotNull)
    def * = (id.?, createdAt, updatedAt, libraryId, ownerId, kind, state, seq) <> ((CortexLibrary.apply _).tupled, CortexLibrary.unapply)
  }

  def table(tag: Tag) = new CortexLibraryTable(tag)
  initTable()

  def invalidateCache(lib: CortexLibrary)(implicit session: RSession): Unit = {}

  def deleteCache(lib: CortexLibrary)(implicit session: RSession): Unit = {}

  def getSince(seq: SequenceNumber[CortexLibrary], limit: Int)(implicit session: RSession): Seq[CortexLibrary] = super.getBySequenceNumber(seq, limit)

  def getMaxSeq()(implicit session: RSession): SequenceNumber[CortexLibrary] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val sql = sql"select max(seq) from cortex_library"
    SequenceNumber[CortexLibrary](sql.as[Long].first max 0L)
  }

  def getByLibraryId(libId: Id[Library])(implicit session: RSession): Option[CortexLibrary] =
    (for { r <- rows if r.libraryId === libId } yield r).firstOption

}
