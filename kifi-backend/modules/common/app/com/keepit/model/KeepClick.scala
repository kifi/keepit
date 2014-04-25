package com.keepit.model

import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.search.ArticleSearchResult

case class KeepClick(
  id: Option[Id[KeepClick]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[KeepClick] = KeepClicksStates.ACTIVE,

  uuid: ExternalId[ArticleSearchResult],
  numKeepers: Int,

  keeperId: Id[User],
  keepId: Id[Keep],
  uriId: Id[NormalizedURI],

  clickerId: Id[User]

) extends ModelWithState[KeepClick] {
  def withId(id: Id[KeepClick]): KeepClick = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepClick = this.copy(updatedAt = now)
}

object KeepClicksStates extends States[KeepClick]