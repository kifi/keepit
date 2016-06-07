package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.{ Library, Organization, User }
import com.keepit.search.util.LongArraySet
import com.kifi.macros.json

sealed trait SearchScope

@json
case class LibraryScope(ids: Set[Id[Library]], authorized: Boolean) extends SearchScope
@json
case class OrganizationScope(id: Id[Organization], authorized: Boolean) extends SearchScope
@json
case class UserScope(id: Id[User]) extends SearchScope
@json
case class SourceScope(source: String) extends SearchScope
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
    libraries: Option[LibraryScope],
    organization: Option[OrganizationScope],
    source: Option[SourceScope]) {

  import ProximityScope._

  val isDefault: Boolean = (this == SearchFilter.default)

  val includeMine: Boolean = !proximity.exists(_ == network)
  val includeNetwork: Boolean = !proximity.exists(_ == mine)
  val includeOthers: Boolean = !proximity.exists(Set(mine, network).contains)

  val userId = user.map(_.id.id) getOrElse -1L
  val libraryIds = libraries.map(scope => LongArraySet.fromSet(scope.ids.map(_.id))) getOrElse LongArraySet.empty
  val orgId = organization.map(_.id.id) getOrElse -1L
}

object SearchFilter {
  val default = SearchFilter(None, None, None, None, None)
}
