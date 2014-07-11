package com.keepit.model

import com.google.inject.{Provider, Inject, Singleton, ImplementedBy}
import com.keepit.common.db.{State, ExternalId, Id}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import scala.slick.lifted.{TableQuery, Tag}

@ImplementedBy(classOf[LibraryRepoImpl])
trait LibraryRepo extends Repo[Library] with RepoWithDelete[Library] with ExternalIdColumnFunction[Library] with SeqNumberFunction[Library] {

 }

@Singleton
class LibraryRepoImpl @Inject()(
                                 val db: DataBaseComponent,
                                 val clock: Clock,
                                 val externalIdCache: LibraryExternalIdCache,
                                 val idCache: LibraryIdCache)
  extends DbRepo[Library] with DbRepoWithDelete[Library] with LibraryRepo with ExternalIdColumnDbFunction[Library] with SeqNumberDbFunction[Library] with Logging {

  import scala.slick.lifted.Query
  import DBSession._
  import db.Driver.simple._
  private val sequence = db.getSequence[Library]("library_sequence")

  type RepoImpl = LibraryTable
  class LibraryTable(tag: Tag) extends RepoTable[Library](db, tag, "library") with ExternalIdColumn[Library] with SeqNumberColumn[Library] {
    def name = column[String]("name", O.NotNull)
    def ownerId = column[Id[User]]("userId", O.Nullable)
    def privacy = column[LibraryPrivacy]("privacy", O.NotNull)
    def description = column[Option[String]]("description", O.NotNull)
    def * = (id.?, createdAt, updatedAt, externalId, name, ownerId, privacy, description, state, seq) <> ((Library.apply _).tupled, Library.unapply)
  }

  def table(tag: Tag) = new LibraryTable(tag)
  initTable()

  private def getLibrary(id: Column[Id[Library]]) =
    for(f <- rows if f.id is id) yield f
  private val getCompiled = Compiled(getLibrary _)

  override def get(id: Id[Library])(implicit session: RSession): Library = {
    idCache.getOrElse(LibraryIdKey(id)) {
      getCompiled(id).first
    }
  }
  override def getOpt(id: ExternalId[Library])(implicit session: RSession): Option[Library] = {
    externalIdCache.getOrElseOpt(LibraryExternalIdKey(id)) {
      (for(f <- rows if f.externalId === id) yield f).firstOption
    }
  }

  override def deleteCache(library: Library)(implicit session: RSession): Unit = {
    idCache.remove(LibraryIdKey(library.id.get))
    externalIdCache.remove(LibraryExternalIdKey(library.externalId))
  }

  override def invalidateCache(library: Library)(implicit session: RSession): Unit = {
    if (library.state == LibraryStates.INACTIVE) {
      deleteCache(library)
    } else {
      idCache.set(LibraryIdKey(library.id.get), library)
      externalIdCache.set(LibraryExternalIdKey(library.externalId), library)
    }
  }


}
