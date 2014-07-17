package com.keepit.model

import com.google.inject.{ Provider, Inject, Singleton, ImplementedBy }
import com.keepit.common.db.{ State, ExternalId, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import scala.slick.lifted.{ TableQuery, Tag }

@ImplementedBy(classOf[LibraryRepoImpl])
trait LibraryRepo extends Repo[Library] with SeqNumberFunction[Library] {

}

@Singleton
class LibraryRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val idCache: LibraryIdCache)
    extends DbRepo[Library] with LibraryRepo with SeqNumberDbFunction[Library] with Logging {

  import scala.slick.lifted.Query
  import DBSession._
  import db.Driver.simple._
  private val sequence = db.getSequence[Library]("library_sequence")

  type RepoImpl = LibraryTable
  class LibraryTable(tag: Tag) extends RepoTable[Library](db, tag, "library") with SeqNumberColumn[Library] {
    def name = column[String]("name", O.NotNull)
    def ownerId = column[Id[User]]("owner_id", O.Nullable)
    def visibility = column[LibraryVisibility]("visibility", O.NotNull)
    def description = column[Option[String]]("description", O.Nullable)
    def slug = column[LibrarySlug]("slug", O.NotNull)
    def kind = column[LibraryKind]("kind", O.NotNull)
    def * = (id.?, createdAt, updatedAt, name, ownerId, visibility, description, slug, state, seq, kind) <> ((Library.apply _).tupled, Library.unapply)
  }

  def table(tag: Tag) = new LibraryTable(tag)
  initTable()

  override def get(id: Id[Library])(implicit session: RSession): Library = {
    idCache.getOrElse(LibraryIdKey(id)) {
      getCompiled(id).first
    }
  }

  override def deleteCache(library: Library)(implicit session: RSession): Unit = {
    idCache.remove(LibraryIdKey(library.id.get))
  }

  override def invalidateCache(library: Library)(implicit session: RSession): Unit = {
    if (library.state == LibraryStates.INACTIVE) {
      deleteCache(library)
    } else {
      idCache.set(LibraryIdKey(library.id.get), library)
    }
  }

}
