package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock

@ImplementedBy(classOf[LibraryInviteRepoImpl])
trait LibraryInviteRepo extends Repo[LibraryInvite] with RepoWithDelete[LibraryInvite] with SeqNumberFunction[LibraryInvite]

@Singleton
class LibraryInviteRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val inviteIdCache: LibraryInviteIdCache)
    extends DbRepo[LibraryInvite] with DbRepoWithDelete[LibraryInvite] with LibraryInviteRepo with SeqNumberDbFunction[LibraryInvite] with Logging {

  import scala.slick.lifted.Query
  import DBSession._
  import db.Driver.simple._
  private val sequence = db.getSequence[LibraryInvite]("library_invite_sequence")

  type RepoImpl = LibraryInviteTable

  class LibraryInviteTable(tag: Tag) extends RepoTable[LibraryInvite](db, tag, "library_invite") with SeqNumberColumn[LibraryInvite] {
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def ownerId = column[Id[User]]("owner_id", O.Nullable)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def access = column[LibraryAccess]("access", O.NotNull)
    def * = (id.?, libraryId, ownerId, userId, access, createdAt, updatedAt, state, seq) <> ((LibraryInvite.apply _).tupled, LibraryInvite.unapply)
  }

  def table(tag: Tag) = new LibraryInviteTable(tag)

  initTable()

  private val getCompiled = {
    def getLibraryInvite(id: Column[Id[LibraryInvite]]) =
      for (f <- rows if f.id is id) yield f
    Compiled(getLibraryInvite _)
  }

  override def get(id: Id[LibraryInvite])(implicit session: RSession): LibraryInvite = {
    inviteIdCache.getOrElse(LibraryInviteIdKey(id)) {
      getCompiled(id).first
    }
  }

  override def deleteCache(libInv: LibraryInvite)(implicit session: RSession): Unit = {
    inviteIdCache.remove(LibraryInviteIdKey(libInv.id.get))
  }

  override def invalidateCache(libInv: LibraryInvite)(implicit session: RSession): Unit = {
    if (libInv.state == LibraryInviteStates.INACTIVE) {
      deleteCache(libInv)
    } else {
      inviteIdCache.set(LibraryInviteIdKey(libInv.id.get), libInv)
    }
  }

}
