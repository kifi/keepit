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
  def getByIdAndOwner(libraryId: Id[Library], ownerId: Id[User])(implicit session: RSession): Option[Library]
  def getByNameAndUser(userId: Id[User], name: String)(implicit session: RSession): Option[Library]
  def getByUser(userId: Id[User], excludeState: Option[State[Library]] = Some(LibraryStates.INACTIVE))(implicit session: RSession): Seq[(LibraryAccess, Library)]
}

@Singleton
class LibraryRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val libraryMembershipRepo: LibraryMembershipRepoImpl,
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

  private def getIdAndUserCompiled(libraryId: Column[Id[Library]], ownerId: Column[Id[User]]) =
    Compiled { (for (b <- rows if b.id === libraryId && b.ownerId === ownerId) yield b).sortBy(_.createdAt) }
  def getByIdAndOwner(libraryId: Id[Library], ownerId: Id[User])(implicit session: RSession): Option[Library] = {
    getIdAndUserCompiled(libraryId, ownerId).firstOption
  }

  private def getByNameAndUserCompiled(userId: Column[Id[User]], name: Column[String]) =
    Compiled { (for (b <- rows if b.name === name && b.ownerId === userId) yield b).sortBy(_.createdAt) }
  def getByNameAndUser(userId: Id[User], name: String)(implicit session: RSession): Option[Library] = {
    getByNameAndUserCompiled(userId, name).firstOption
  }

  def getByUser(userId: Id[User], excludeState: Option[State[Library]])(implicit session: RSession): Seq[(LibraryAccess, Library)] = {
    val q = for {
      lib <- rows if lib.state =!= excludeState.orNull
      lm <- libraryMembershipRepo.rows if lm.libraryId === lib.id && lm.userId === userId && lm.state === LibraryMembershipStates.ACTIVE
    } yield (lm.access, lib)
    q.list
  }

}
