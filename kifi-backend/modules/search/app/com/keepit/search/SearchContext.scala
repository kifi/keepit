package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.{ Library, Organization, User }
import com.keepit.search.util.{ LongArraySet, IdFilterCompressor }
import com.kifi.macros.json

sealed trait SearchScope

@json
case class LibraryScope(id: Id[Library], authorized: Boolean) extends SearchScope
@json
case class OrganizationScope(id: Id[Organization], authorized: Boolean) extends SearchScope
@json
case class UserScope(id: Id[User]) extends SearchScope
@json
case class ProximityScope(proximity: String) extends SearchScope

object ProximityScope {
  val mine = ProximityScope("m")
  val network = ProximityScope("f")
  val all = ProximityScope("a")
  private val valid = Set(mine, network, all)
  def parse(proximity: String): Option[ProximityScope] = Some(ProximityScope(proximity.toLowerCase.trim)).filter(valid.contains)
}

@json
case class SearchFilter(
    proximity: Option[ProximityScope],
    user: Option[UserScope],
    library: Option[LibraryScope],
    organization: Option[OrganizationScope]) {

  import ProximityScope._

  val isDefault: Boolean = proximity.isEmpty && user.isEmpty && library.isEmpty && organization.isEmpty

  val includeMine: Boolean = !proximity.exists(_ == network)
  val includeNetwork: Boolean = !proximity.exists(_ == mine)
  val includeOthers: Boolean = !proximity.exists(Set(mine, network).contains)

  val userId = user.map(_.id.id) getOrElse -1L
  val libraryId = library.map(_.id.id) getOrElse -1L
  val orgId = organization.map(_.id.id) getOrElse -1L
}

object SearchFilter {
  val empty = SearchFilter(None, None, None, None)
}

@json
case class SearchContext(context: Option[String], orderBy: SearchRanking, filter: SearchFilter, disablePrefixSearch: Boolean, disableFullTextSearch: Boolean) {
  lazy val idFilter: LongArraySet = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))
  val isDefault = filter.isDefault
}
