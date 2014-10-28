package com.keepit.model

import com.keepit.common.db._
import org.joda.time.DateTime
import com.keepit.common.time._
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

case class LibraryAlias(
    id: Option[Id[LibraryAlias]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryAlias] = LibraryAliasStates.ACTIVE,
    ownerId: Id[User],
    slug: LibrarySlug,
    libraryId: Id[Library]) extends ModelWithState[LibraryAlias] {
  def withId(id: Id[LibraryAlias]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive = (state == LibraryAliasStates.ACTIVE)
}

object LibraryAliasStates extends States[LibraryAlias]

@ImplementedBy(classOf[LibraryAliasRepoImpl])
trait LibraryAliasRepo extends Repo[LibraryAlias] {
  def getByOwnerIdAndSlug(ownerId: Id[User], slug: LibrarySlug, excludeState: Option[State[LibraryAlias]] = Some(LibraryAliasStates.INACTIVE))(implicit session: RSession): Option[LibraryAlias]
  def intern(ownerId: Id[User], slug: LibrarySlug, libraryId: Id[Library])(implicit session: RWSession): LibraryAlias
}

@Singleton
class LibraryAliasRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[LibraryAlias] with LibraryAliasRepo with Logging {

  import db.Driver.simple._

  type RepoImpl = LibraryAliasTable
  class LibraryAliasTable(tag: Tag) extends RepoTable[LibraryAlias](db, tag, "library_alias") {
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    def slug = column[LibrarySlug]("slug", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, ownerId, slug, libraryId) <> ((LibraryAlias.apply _).tupled, LibraryAlias.unapply _)
  }

  def table(tag: Tag) = new LibraryAliasTable(tag)
  initTable

  override def invalidateCache(slugAlias: LibraryAlias)(implicit session: RSession): Unit = {}

  override def deleteCache(slugAlias: LibraryAlias)(implicit session: RSession): Unit = {}

  private def compiledGetByUserIdAndSlug(ownerId: Id[User], slug: LibrarySlug, excludeState: Option[State[LibraryAlias]]) = Compiled {
    for (row <- rows if row.ownerId === ownerId && row.slug === slug && row.state =!= excludeState.orNull) yield row
  }

  def getByOwnerIdAndSlug(ownerId: Id[User], slug: LibrarySlug, excludeState: Option[State[LibraryAlias]] = Some(LibraryAliasStates.INACTIVE))(implicit session: RSession): Option[LibraryAlias] = {
    compiledGetByUserIdAndSlug(ownerId, slug, excludeState).firstOption
  }

  def intern(ownerId: Id[User], slug: LibrarySlug, libraryId: Id[Library])(implicit session: RWSession): LibraryAlias = {
    getByOwnerIdAndSlug(ownerId, slug, excludeState = None) match {
      case None => save(LibraryAlias(ownerId = ownerId, slug = slug, libraryId = libraryId))
      case Some(inactiveAlias) if inactiveAlias.state == LibraryAliasStates.INACTIVE => {
        save(inactiveAlias.copy(createdAt = clock.now, updatedAt = clock.now, state = LibraryAliasStates.ACTIVE, libraryId = libraryId))
      }
      case Some(validAlias) if validAlias.libraryId == libraryId => validAlias
      case Some(invalidAlias) => save(invalidAlias.copy(libraryId = libraryId))
    }
  }

}
