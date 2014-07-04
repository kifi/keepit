package com.keepit.model

import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.search.ArticleSearchResult

case class KeepDiscovery(
  id: Option[Id[KeepDiscovery]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[KeepDiscovery] = KeepDiscoveryStates.ACTIVE,

  hitUUID: ExternalId[ArticleSearchResult],
  numKeepers: Int,

  keeperId: Id[User],
  keepId: Id[Keep],
  uriId: Id[NormalizedURI],
  origin: Option[String] = None

) extends ModelWithState[KeepDiscovery] {
  def withId(id: Id[KeepDiscovery]): KeepDiscovery = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepDiscovery = this.copy(updatedAt = now)
}

object KeepDiscoveryStates extends States[KeepDiscovery]

case class RichKeepDiscovery(id:Option[Id[KeepDiscovery]], createdAt:DateTime, updatedAt:DateTime, state:State[KeepDiscovery], hitUUID:ExternalId[ArticleSearchResult], numKeepers:Int, keeper:User, keep:Keep, uri:NormalizedURI, origin:Option[String])