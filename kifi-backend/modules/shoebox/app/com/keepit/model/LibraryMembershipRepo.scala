package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock

@ImplementedBy(classOf[LibraryMembershipRepoImpl])
trait LibraryMembershipRepo extends Repo[LibraryMembership] with RepoWithDelete[LibraryMembership] with SeqNumberFunction[LibraryMembership]


@Singleton
class LibraryMembershipRepoImpl @Inject()(
                                 val db: DataBaseComponent,
                                 val clock: Clock,
                                 val memberIdCache: LibraryMembershipIdCache)
  extends DbRepo[LibraryMembership] with DbRepoWithDelete[LibraryMembership] with LibraryMembershipRepo with SeqNumberDbFunction[LibraryMembership] with Logging {

  import scala.slick.lifted.Query
  import DBSession._
  import db.Driver.simple._
  private val sequence = db.getSequence[LibraryMembership]("library_member_sequence")

  type RepoImpl = LibraryMemberTable

  class LibraryMemberTable(tag: Tag) extends RepoTable[LibraryMembership](db, tag, "library_member") with SeqNumberColumn[LibraryMembership] {
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def permission = column[LibraryMembershipAccess]("privacy", O.NotNull)
    def * = (id.?, libraryId, userId, permission, createdAt, updatedAt, state, seq) <>((LibraryMembership.apply _).tupled, LibraryMembership.unapply)
  }

  def table(tag: Tag) = new LibraryMemberTable(tag)

  initTable()


  private val getCompiled = {
    def getLibraryMember(id: Column[Id[LibraryMembership]]) =
      for(f <- rows if f.id is id) yield f
    Compiled(getLibraryMember _)
  }

  override def get(id: Id[LibraryMembership])(implicit session: RSession): LibraryMembership = {
    memberIdCache.getOrElse(LibraryMembershipIdKey(id)) {
      getCompiled(id).first
    }
  }

  override def deleteCache(libMem: LibraryMembership)(implicit session: RSession): Unit = {
    memberIdCache.remove(LibraryMembershipIdKey(libMem.id.get))
  }

  override def invalidateCache(libMem: LibraryMembership)(implicit session: RSession): Unit = {
    if (libMem.state == LibraryMembershipStates.INACTIVE) {
      deleteCache(libMem)
    } else {
      memberIdCache.set(LibraryMembershipIdKey(libMem.id.get), libMem)
    }
  }

}
