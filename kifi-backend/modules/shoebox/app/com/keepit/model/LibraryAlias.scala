package com.keepit.model

import com.keepit.common.db._
import com.keepit.model.LibrarySpace._
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
    space: LibrarySpace,
    slug: LibrarySlug,
    libraryId: Id[Library]) extends ModelWithState[LibraryAlias] {
  def withId(id: Id[LibraryAlias]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def isActive = (state == LibraryAliasStates.ACTIVE)
}

object LibraryAlias {

  case class InvalidLibraryAliasException(id: Option[Id[LibraryAlias]], ownerIdOpt: Option[Id[User]], organizationIdOpt: Option[Id[Organization]], slug: LibrarySlug, libraryId: Id[Library])
    extends Exception(s"Invalid alias $id from (user $ownerIdOpt, org $organizationIdOpt, slug $slug) to library $libraryId")

  def applyFromDbRow(
    id: Option[Id[LibraryAlias]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryAlias] = LibraryAliasStates.ACTIVE,
    ownerIdOpt: Option[Id[User]],
    organizationIdOpt: Option[Id[Organization]],
    slug: LibrarySlug,
    libraryId: Id[Library]) = {
    val space: LibrarySpace = (ownerIdOpt, organizationIdOpt) match {
      case (Some(userId), None) => userId
      case (None, Some(organizationId)) => organizationId
      case _ => throw new InvalidLibraryAliasException(id, ownerIdOpt, organizationIdOpt, slug, libraryId)
    }
    LibraryAlias(id, createdAt, updatedAt, state, space, slug, libraryId)
  }

  def unapplyToDbRow(alias: LibraryAlias) = {
    val (userIdOpt, organizationIdOpt) = alias.space match {
      case UserSpace(userId) => (Some(userId), None)
      case OrganizationSpace(organizationId) => (None, Some(organizationId))
    }
    Some((
      alias.id,
      alias.createdAt,
      alias.updatedAt,
      alias.state,
      userIdOpt,
      organizationIdOpt,
      alias.slug,
      alias.libraryId
    ))
  }
}

object LibraryAliasStates extends States[LibraryAlias]

@ImplementedBy(classOf[LibraryAliasRepoImpl])
trait LibraryAliasRepo extends Repo[LibraryAlias] {
  def getBySpaceAndSlug(space: LibrarySpace, slug: LibrarySlug, excludeState: Option[State[LibraryAlias]] = Some(LibraryAliasStates.INACTIVE))(implicit session: RSession): Option[LibraryAlias]
  def alias(space: LibrarySpace, slug: LibrarySlug, libraryId: Id[Library])(implicit session: RWSession): LibraryAlias
  def reclaim(space: LibrarySpace, slug: LibrarySlug)(implicit session: RWSession): Option[Id[Library]]
}

@Singleton
class LibraryAliasRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[LibraryAlias] with LibraryAliasRepo with Logging {

  import db.Driver.simple._

  type RepoImpl = LibraryAliasTable
  class LibraryAliasTable(tag: Tag) extends RepoTable[LibraryAlias](db, tag, "library_alias") {
    def ownerId = column[Option[Id[User]]]("owner_id", O.Nullable)
    def organizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)
    def slug = column[LibrarySlug]("slug", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, ownerId, organizationId, slug, libraryId) <> ((LibraryAlias.applyFromDbRow _).tupled, LibraryAlias.unapplyToDbRow _)
  }

  def table(tag: Tag) = new LibraryAliasTable(tag)
  initTable

  override def invalidateCache(slugAlias: LibraryAlias)(implicit session: RSession): Unit = {}

  override def deleteCache(slugAlias: LibraryAlias)(implicit session: RSession): Unit = {}

  private val compiledGetByOrganizationIdAndSlug = Compiled {
    (organizationId: Column[Id[Organization]], slug: Column[LibrarySlug]) =>
      for (row <- rows if row.organizationId === organizationId && row.slug === slug) yield row
  }

  private val compiledGetByOrganizationIdAndSlugAndExcludedState = Compiled {
    (organizationId: Column[Id[Organization]], slug: Column[LibrarySlug], excludedState: Column[State[LibraryAlias]]) =>
      for (row <- rows if row.organizationId === organizationId && row.slug === slug && row.state =!= excludedState) yield row
  }

  private val compiledGetByUserIdAndSlug = Compiled {
    (ownerId: Column[Id[User]], slug: Column[LibrarySlug]) =>
      for (row <- rows if row.ownerId === ownerId && row.slug === slug) yield row
  }

  private val compiledGetByUserIdAndSlugAndExcludedState = Compiled {
    (ownerId: Column[Id[User]], slug: Column[LibrarySlug], excludedState: Column[State[LibraryAlias]]) =>
      for (row <- rows if row.ownerId === ownerId && row.slug === slug && row.state =!= excludedState) yield row
  }

  def getBySpaceAndSlug(space: LibrarySpace, slug: LibrarySlug, excludeState: Option[State[LibraryAlias]] = Some(LibraryAliasStates.INACTIVE))(implicit session: RSession): Option[LibraryAlias] = {
    val q = space match {
      case OrganizationSpace(orgId) => excludeState.map(compiledGetByOrganizationIdAndSlugAndExcludedState(orgId, slug, _)) getOrElse compiledGetByOrganizationIdAndSlug(orgId, slug)
      case UserSpace(userId) => excludeState.map(compiledGetByUserIdAndSlugAndExcludedState(userId, slug, _)) getOrElse compiledGetByUserIdAndSlug(userId, slug)
    }
    q.firstOption
  }

  def alias(space: LibrarySpace, slug: LibrarySlug, libraryId: Id[Library])(implicit session: RWSession): LibraryAlias = {
    getBySpaceAndSlug(space, slug, excludeState = None) match {
      case None => save(LibraryAlias(space = space, slug = slug, libraryId = libraryId))
      case Some(existingAlias) => {
        val requestedAlias = existingAlias.copy(state = LibraryAliasStates.ACTIVE, slug = slug, libraryId = libraryId)
        if (requestedAlias == existingAlias) requestedAlias else save(requestedAlias)
      }
    }
  }

  def reclaim(space: LibrarySpace, slug: LibrarySlug)(implicit session: RWSession): Option[Id[Library]] = {
    getBySpaceAndSlug(space, slug).collect {
      case alias if alias.state == LibraryAliasStates.ACTIVE =>
        log.info(s"Reclaiming ${LibrarySpace.prettyPrint(alias.space)}'s alias from ${alias.slug} to former library ${alias.libraryId}")
        save(alias.copy(state = LibraryAliasStates.INACTIVE))
        alias.libraryId
    }
  }
}
