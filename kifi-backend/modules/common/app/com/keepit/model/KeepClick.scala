package com.keepit.model

import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.search.ArticleSearchResult
import com.keepit.heimdal.SanitizedKifiHit

case class KeepClick(
  id: Option[Id[KeepClick]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[KeepClick] = KeepClickStates.ACTIVE,

  hitUUID: ExternalId[SanitizedKifiHit],
  numKeepers: Int,

  keeperId: Id[User],
  keepId: Id[Keep],
  uriId: Id[NormalizedURI],
  origin: Option[String] = None

) extends ModelWithState[KeepClick] {
  def withId(id: Id[KeepClick]): KeepClick = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepClick = this.copy(updatedAt = now)
}

object KeepClickStates extends States[KeepClick]

case class RichKeepClick(id:Option[Id[KeepClick]], createdAt:DateTime, updatedAt:DateTime, state:State[KeepClick], hitUUID:ExternalId[SanitizedKifiHit], numKeepers:Int, keeper:User, keep:Keep, uri:NormalizedURI, origin:Option[String])