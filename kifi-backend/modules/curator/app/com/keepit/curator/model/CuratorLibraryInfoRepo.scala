package com.keepit.curator.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.time.Clock
import com.keepit.model.{LibraryKind, Library, User, NormalizedURI}

@ImplementedBy(classOf[CuratorLibraryInfoRepoImpl])
trait CuratorLibraryInfoRepo extends DbRepo[CuratorLibraryInfo] {
  def getByLibraryId(libId: Id[Library])(implicit session: RSession): Option[CuratorLibraryInfo]
  def getLibrariesByUserIdAndUriId(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Id[Library]]
}

@Singleton
class CuratorLibraryInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
  extends DbRepo[CuratorLibraryInfo] with CuratorLibraryInfoRepo {

  import db.Driver.simple._

  type RepoImpl = CuratorLibraryInfoTable
  class CuratorLibraryInfoTable(tag: Tag) extends RepoTable[CuratorLibraryInfo](db, tag, "curator_library_info") {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def userIds = column[Set[Id[User]]]("user_id", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def kind = column[LibraryKind]("library_kind", O.NotNull)
    def * = (id.?, createdAt, updatedAt, uriId, userIds, libraryId, kind) <> ((CuratorLibraryInfo.apply _).tupled, CuratorLibraryInfo.unapply _)
  }

  def table(tag: Tag) = new CuratorLibraryInfoTable(tag)
  initTable()

  def deleteCache(model: CuratorLibraryInfo)(implicit session: RSession): Unit = {}
  def invalidateCache(model: CuratorLibraryInfo)(implicit session: RSession): Unit = {}

  def getByLibraryId(libId: Id[Library])(implicit session: RSession): Option[CuratorLibraryInfo] = {
    (for (row <- rows if row.libraryId === libId) yield row).firstOption
  }

  def getLibrariesByUserIdAndUriId(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Seq[Id[Library]] = {
    (for (r <- rows if r.userIds. === userId && r.uriId === uriId) yield r.libraryId).list.distinct
  }
}

