package com.keepit.curator.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.time.Clock
import com.keepit.model.{ LibraryAccess, LibraryKind, Library, User, NormalizedURI }

import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[CuratorLibraryMembershipInfoRepoImpl])
trait CuratorLibraryMembershipInfoRepo extends DbRepo[CuratorLibraryMembershipInfo] {
  def getByUserAndLibraryId(userId: Id[User], libId: Id[Library])(implicit session: RSession): Option[CuratorLibraryMembershipInfo]
  def getLibrariesByUserId(userId: Id[User])(implicit session: RSession): Seq[Id[Library]]
  def getUsersFollowingALibrary()(implicit session: RSession): Set[Id[User]]
  def getFollowedLibrariesWithUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Set[Id[Library]]
  def getByLibrary(libraryId: Id[Library])(implicit session: RSession): Seq[CuratorLibraryMembershipInfo]
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
    def access = column[LibraryAccess]("library_access", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, libraryId, access, state) <> ((CuratorLibraryMembershipInfo.apply _).tupled, CuratorLibraryMembershipInfo.unapply _)
  }

  def table(tag: Tag) = new CuratorLibraryMembershipInfoTable(tag)
  initTable()

  def deleteCache(model: CuratorLibraryMembershipInfo)(implicit session: RSession): Unit = {}
  def invalidateCache(model: CuratorLibraryMembershipInfo)(implicit session: RSession): Unit = {}

  def getByUserAndLibraryId(userId: Id[User], libId: Id[Library])(implicit session: RSession): Option[CuratorLibraryMembershipInfo] = {
    (for (row <- rows if row.userId === userId && row.libraryId === libId) yield row).firstOption
  }

  def getLibrariesByUserId(userId: Id[User])(implicit session: RSession): Seq[Id[Library]] = {
    (for (row <- rows if row.userId === userId && row.state === CuratorLibraryMembershipInfoStates.ACTIVE) yield row.libraryId).list.distinct
  }

  def getUsersFollowingALibrary()(implicit session: RSession): Set[Id[User]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"SELECT DISTINCT(user_id) FROM curator_library_membership_info WHERE state='active' AND library_access='read_only'".as[Id[User]].list.toSet
  }

  def getFollowedLibrariesWithUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Set[Id[Library]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"""
      SELECT libs.library_id
      FROM curator_keep_info keeps, curator_library_membership_info libs
      WHERE
        keeps.library_id=libs.library_id
        AND
        keeps.uri_id=$uriId
        AND
        libs.user_id=$userId
        AND
        keeps.state='active'
        AND
        libs.state='active'
        AND
        libs.library_access='read_only';
    """.as[Id[Library]].list.toSet

  }

  def getByLibrary(libraryId: Id[Library])(implicit session: RSession): Seq[CuratorLibraryMembershipInfo] =
    (for (row <- rows if row.libraryId === libraryId && row.state === CuratorLibraryMembershipInfoStates.ACTIVE) yield row).list
}
