package com.keepit.curator.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.time.Clock
import com.keepit.model.{ LibraryAccess, LibraryKind, Library, User }

@ImplementedBy(classOf[CuratorLibraryMembershipInfoRepoImpl])
trait CuratorLibraryMembershipInfoRepo extends DbRepo[CuratorLibraryMembershipInfo] {
  def getByLibraryId(libId: Id[Library])(implicit session: RSession): Option[CuratorLibraryMembershipInfo]
  def getLibrariesByUserId(userId: Id[User])(implicit session: RSession): Seq[Id[Library]]
}

@Singleton
class CuratorLibraryMembershipInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[CuratorLibraryMembershipInfo] with CuratorLibraryMembershipInfoRepo {

  import db.Driver.simple._

  type RepoImpl = CuratorLibraryMembershipInfoTable
  class CuratorLibraryMembershipInfoTable(tag: Tag) extends RepoTable[CuratorLibraryMembershipInfo](db, tag, "curator_library_membership_info") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def kind = column[LibraryKind]("library_kind", O.NotNull)
    def access = column[LibraryAccess]("library_access", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, libraryId, access, kind, state) <> ((CuratorLibraryMembershipInfo.apply _).tupled, CuratorLibraryMembershipInfo.unapply _)
  }

  def table(tag: Tag) = new CuratorLibraryMembershipInfoTable(tag)
  initTable()

  def deleteCache(model: CuratorLibraryMembershipInfo)(implicit session: RSession): Unit = {}
  def invalidateCache(model: CuratorLibraryMembershipInfo)(implicit session: RSession): Unit = {}

  def getByLibraryId(libId: Id[Library])(implicit session: RSession): Option[CuratorLibraryMembershipInfo] = {
    (for (row <- rows if row.libraryId === libId) yield row).firstOption
  }

  def getLibrariesByUserId(userId: Id[User])(implicit session: RSession): Seq[Id[Library]] = {
    (for (row <- rows if row.userId === userId && row.state === CuratorLibraryMembershipInfoStates.ACTIVE) yield row.libraryId).list.distinct
  }
}

