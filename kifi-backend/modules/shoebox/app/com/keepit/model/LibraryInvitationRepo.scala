package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock

@ImplementedBy(classOf[LibraryInvitationRepoImpl])
trait LibraryInvitationRepo extends Repo[LibraryInvitation] with RepoWithDelete[LibraryInvitation] with SeqNumberFunction[LibraryInvitation]

@Singleton
class LibraryInvitationRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val inviteIdCache: LibraryInvitationIdCache)
    extends DbRepo[LibraryInvitation] with DbRepoWithDelete[LibraryInvitation] with LibraryInvitationRepo with SeqNumberDbFunction[LibraryInvitation] with Logging {

  import scala.slick.lifted.Query
  import DBSession._
  import db.Driver.simple._
  private val sequence = db.getSequence[LibraryInvitation]("library_invite_sequence")

  type RepoImpl = LibraryInviteTable

  class LibraryInviteTable(tag: Tag) extends RepoTable[LibraryInvitation](db, tag, "library_invite") with SeqNumberColumn[LibraryInvitation] {
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def ownerId = column[Id[User]]("owner_id", O.Nullable)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def access = column[LibraryAccess]("access", O.NotNull)
    def * = (id.?, libraryId, ownerId, userId, access, createdAt, updatedAt, state, seq) <> ((LibraryInvitation.apply _).tupled, LibraryInvitation.unapply)
  }

  def table(tag: Tag) = new LibraryInviteTable(tag)

  initTable()

  private val getCompiled = {
    def getLibraryInvite(id: Column[Id[LibraryInvitation]]) =
      for (f <- rows if f.id is id) yield f
    Compiled(getLibraryInvite _)
  }

  override def get(id: Id[LibraryInvitation])(implicit session: RSession): LibraryInvitation = {
    inviteIdCache.getOrElse(LibraryInvitationIdKey(id)) {
      getCompiled(id).first
    }
  }

  override def deleteCache(libInv: LibraryInvitation)(implicit session: RSession): Unit = {
    inviteIdCache.remove(LibraryInvitationIdKey(libInv.id.get))
  }

  override def invalidateCache(libInv: LibraryInvitation)(implicit session: RSession): Unit = {
    if (libInv.state == LibraryInvitationStates.INACTIVE) {
      deleteCache(libInv)
    } else {
      inviteIdCache.set(LibraryInvitationIdKey(libInv.id.get), libInv)
    }
  }

}
