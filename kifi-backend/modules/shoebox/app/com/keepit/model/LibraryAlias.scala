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
  def alias(ownerId: Id[User], slug: LibrarySlug, libraryId: Id[Library])(implicit session: RWSession): LibraryAlias
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

  private val compiledGetByUserIdAndSlug = Compiled {
    (ownerId: Column[Id[User]], slug: Column[LibrarySlug]) =>
      for (row <- rows if row.ownerId === ownerId && row.slug === slug) yield row
  }

  private val compiledGetByUserIdAndSlugAndExcludedState = Compiled {
    (ownerId: Column[Id[User]], slug: Column[LibrarySlug], excludedState: Column[State[LibraryAlias]]) =>
      for (row <- rows if row.ownerId === ownerId && row.slug === slug && row.state =!= excludedState) yield row
  }

  def getByOwnerIdAndSlug(ownerId: Id[User], slug: LibrarySlug, excludeState: Option[State[LibraryAlias]] = Some(LibraryAliasStates.INACTIVE))(implicit session: RSession): Option[LibraryAlias] = {
    val q = excludeState match {
      case Some(state) => compiledGetByUserIdAndSlugAndExcludedState(ownerId, slug, state)
      case None => compiledGetByUserIdAndSlug(ownerId, slug)
    }
    q.firstOption
  }

  def alias(ownerId: Id[User], slug: LibrarySlug, libraryId: Id[Library])(implicit session: RWSession): LibraryAlias = {
    getByOwnerIdAndSlug(ownerId, slug, excludeState = None) match {
      case None => save(LibraryAlias(ownerId = ownerId, slug = slug, libraryId = libraryId))
      case Some(existingAlias) => {
        val requestedAlias = existingAlias.copy(state = LibraryAliasStates.ACTIVE, slug = slug, libraryId = libraryId)
        if (requestedAlias == existingAlias) requestedAlias else save(requestedAlias)
      }
    }
  }

}
