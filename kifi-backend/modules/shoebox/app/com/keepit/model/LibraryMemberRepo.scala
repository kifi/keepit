package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock

@ImplementedBy(classOf[LibraryMemberRepoImpl])
trait LibraryMemberRepo extends Repo[LibraryMember] with RepoWithDelete[LibraryMember] with SeqNumberFunction[LibraryMember]{


}


@Singleton
class LibraryMemberRepoImpl @Inject()(
                                 val db: DataBaseComponent,
                                 val clock: Clock,
                                 val memberIdCache: LibraryMemberIdCache)
  extends DbRepo[LibraryMember] with DbRepoWithDelete[LibraryMember] with LibraryMemberRepo with SeqNumberDbFunction[LibraryMember] with Logging {

  import scala.slick.lifted.Query
  import DBSession._
  import db.Driver.simple._
  private val sequence = db.getSequence[LibraryMember]("library_member_sequence")

  type RepoImpl = LibraryMemberTable

  class LibraryMemberTable(tag: Tag) extends RepoTable[LibraryMember](db, tag, "library_member") with SeqNumberColumn[LibraryMember] {
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def permission = column[LibraryMemberPrivacy]("privacy", O.NotNull)
    def * = (id.?, libraryId, userId, permission, createdAt, updatedAt, state, seq) <>((LibraryMember.apply _).tupled, LibraryMember.unapply)
  }

  def table(tag: Tag) = new LibraryMemberTable(tag)

  initTable()


  private def getLibraryMember(id: Column[Id[LibraryMember]]) =
    for(f <- rows if f.id is id) yield f
  private val getCompiled = Compiled(getLibraryMember _)

  override def get(id: Id[LibraryMember])(implicit session: RSession): LibraryMember = {
    memberIdCache.getOrElse(LibraryMemberIdKey(id)) {
      getCompiled(id).first
    }
  }

  override def deleteCache(libMem: LibraryMember)(implicit session: RSession): Unit = {
    memberIdCache.remove(LibraryMemberIdKey(libMem.id.get))
  }

  override def invalidateCache(libMem: LibraryMember)(implicit session: RSession): Unit = {
    if (libMem.state == LibraryMemberStates.INACTIVE) {
      deleteCache(libMem)
    } else {
      memberIdCache.set(LibraryMemberIdKey(libMem.id.get), libMem)
    }
  }

}