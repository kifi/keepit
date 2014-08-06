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
    origin: Option[String] = None) extends ModelWithState[KeepDiscovery] {
  def withId(id: Id[KeepDiscovery]): KeepDiscovery = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): KeepDiscovery = this.copy(updatedAt = now)
}

object KeepDiscovery {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  implicit val format: Format[KeepDiscovery] = (
    (__ \ 'id).formatNullable(Id.format[KeepDiscovery]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format(State.format[KeepDiscovery]) and
    (__ \ 'hitUUID).format(ExternalId.format[ArticleSearchResult]) and
    (__ \ 'numKeepers).format[Int] and
    (__ \ 'keeperId).format(Id.format[User]) and
    (__ \ 'keepId).format(Id.format[Keep]) and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'origin).formatNullable[String]
  )(KeepDiscovery.apply, unlift(KeepDiscovery.unapply))
}

object KeepDiscoveryStates extends States[KeepDiscovery]

case class RichKeepDiscovery(id: Option[Id[KeepDiscovery]], createdAt: DateTime, updatedAt: DateTime, state: State[KeepDiscovery], hitUUID: ExternalId[ArticleSearchResult], numKeepers: Int, keeper: User, keep: Keep, uri: NormalizedURI, origin: Option[String])