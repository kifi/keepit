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
    organization: Option[OrganizationScope],
    context: Option[String]) {

  import ProximityScope._

  lazy val idFilter: LongArraySet = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))

  val isDefault: Boolean = proximity.isEmpty
  val includeMine: Boolean = !proximity.exists(_ == network)
  val includeNetwork: Boolean = !proximity.exists(_ == mine)
  val includeOthers: Boolean = !proximity.exists(Set(mine, network).contains)
}

object SearchFilter {
  val default = SearchFilter(None, None, None, None, None)
}
