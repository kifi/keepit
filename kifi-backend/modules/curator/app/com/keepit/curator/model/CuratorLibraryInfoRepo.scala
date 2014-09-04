package com.keepit.curator.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.time.Clock
import com.keepit.model.{ LibraryMembership, LibraryKind, Library, User, NormalizedURI }

@ImplementedBy(classOf[CuratorLibraryInfoRepoImpl])
trait CuratorLibraryInfoRepo extends DbRepo[CuratorLibraryInfo] {
  def getByLibraryId(libId: Id[Library])(implicit session: RSession): Option[CuratorLibraryInfo]
  def getLibrariesByUserId(userId: Id[User])(implicit session: RSession): Seq[Id[Library]]
}

@Singleton
class CuratorLibraryInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[CuratorLibraryInfo] with CuratorLibraryInfoRepo {

  import db.Driver.simple._

  type RepoImpl = CuratorLibraryInfoTable
  class CuratorLibraryInfoTable(tag: Tag) extends RepoTable[CuratorLibraryInfo](db, tag, "curator_library_info") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def kind = column[LibraryKind]("library_kind", O.NotNull)
    def membershipState = column[State[LibraryMembership]]("membership_state", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, libraryId, kind, state, membershipState) <> ((CuratorLibraryInfo.apply _).tupled, CuratorLibraryInfo.unapply _)
  }

  def table(tag: Tag) = new CuratorLibraryInfoTable(tag)
  initTable()

  def deleteCache(model: CuratorLibraryInfo)(implicit session: RSession): Unit = {}
  def invalidateCache(model: CuratorLibraryInfo)(implicit session: RSession): Unit = {}

  def getByLibraryId(libId: Id[Library])(implicit session: RSession): Option[CuratorLibraryInfo] = {
    (for (row <- rows if row.libraryId === libId) yield row).firstOption
  }

  def getLibrariesByUserId(userId: Id[User])(implicit session: RSession): Seq[Id[Library]] = {
    (for (r <- rows if r.userId === userId) yield r.libraryId).list.distinct
  }
}

