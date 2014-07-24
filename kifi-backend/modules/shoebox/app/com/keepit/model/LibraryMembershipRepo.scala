package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ State, ExternalId, Id }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock

@ImplementedBy(classOf[LibraryMembershipRepoImpl])
trait LibraryMembershipRepo extends Repo[LibraryMembership] with RepoWithDelete[LibraryMembership] with SeqNumberFunction[LibraryMembership] {
  def getWithLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership]
  def getWithUserId(userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership]
  def getWithLibraryIdandUserId(libraryId: Id[Library], userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Option[LibraryMembership]
}

@Singleton
class LibraryMembershipRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val memberIdCache: LibraryMembershipIdCache)
    extends DbRepo[LibraryMembership] with DbRepoWithDelete[LibraryMembership] with LibraryMembershipRepo with SeqNumberDbFunction[LibraryMembership] with Logging {

  import DBSession._
  import db.Driver.simple._
  private val sequence = db.getSequence[LibraryMembership]("library_membership_sequence")

  type RepoImpl = LibraryMemberTable

  class LibraryMemberTable(tag: Tag) extends RepoTable[LibraryMembership](db, tag, "library_membership") with SeqNumberColumn[LibraryMembership] {
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def access = column[LibraryAccess]("access", O.NotNull)
    def showInSearch = column[Boolean]("show_in_search", O.NotNull)
    def * = (id.?, libraryId, userId, access, createdAt, updatedAt, state, seq, showInSearch) <> ((LibraryMembership.apply _).tupled, LibraryMembership.unapply)
  }

  def table(tag: Tag) = new LibraryMemberTable(tag)

  initTable()

  override def get(id: Id[LibraryMembership])(implicit session: RSession): LibraryMembership = {
    memberIdCache.getOrElse(LibraryMembershipIdKey(id)) {
      getCompiled(id).first
    }
  }

  def getWithLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership] = {
    (for (b <- rows if b.libraryId === libraryId && b.state =!= excludeState.orNull) yield b).sortBy(_.createdAt).list
  }
  def getWithUserId(userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Seq[LibraryMembership] = {
    (for (b <- rows if b.userId === userId && b.state =!= excludeState.orNull) yield b).sortBy(_.createdAt).list
  }
  def getWithLibraryIdandUserId(libraryId: Id[Library], userId: Id[User], excludeState: Option[State[LibraryMembership]] = Some(LibraryMembershipStates.INACTIVE))(implicit session: RSession): Option[LibraryMembership] = {
    (for (b <- rows if b.libraryId === libraryId && b.userId === userId && b.state =!= excludeState.orNull) yield b).sortBy(_.createdAt).firstOption
  }

  override def deleteCache(libMem: LibraryMembership)(implicit session: RSession): Unit = {
    libMem.id.map { id =>
      memberIdCache.remove(LibraryMembershipIdKey(id))
    }
  }

  override def invalidateCache(libMem: LibraryMembership)(implicit session: RSession): Unit = {
    libMem.id.map { id =>
      if (libMem.state == LibraryMembershipStates.INACTIVE) {
        deleteCache(libMem)
      } else {
        memberIdCache.set(LibraryMembershipIdKey(id), libMem)
      }
    }
  }

}
