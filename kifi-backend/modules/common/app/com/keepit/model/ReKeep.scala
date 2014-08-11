package com.keepit.model

import com.kifi.macros.json
import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._

case class ReKeep(
    id: Option[Id[ReKeep]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[ReKeep] = ReKeepStates.ACTIVE,

    keeperId: Id[User],
    keepId: Id[Keep],
    uriId: Id[NormalizedURI],

    srcUserId: Id[User],
    srcKeepId: Id[Keep],

    attributionFactor: Int = 1) extends ModelWithState[ReKeep] {
  def withId(id: Id[ReKeep]): ReKeep = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): ReKeep = this.copy(updatedAt = now)
}

object ReKeep {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  implicit val format: Format[ReKeep] = (
    (__ \ 'id).formatNullable(Id.format[ReKeep]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format(State.format[ReKeep]) and
    (__ \ 'keeperId).format(Id.format[User]) and
    (__ \ 'keepId).format(Id.format[Keep]) and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'srcUserId).format(Id.format[User]) and
    (__ \ 'srcKeepId).format(Id.format[Keep]) and
    (__ \ 'attributionFactor).format[Int]
  )(ReKeep.apply, unlift(ReKeep.unapply))
}

object ReKeepStates extends States[ReKeep]

case class RichReKeep(id: Option[Id[ReKeep]], createdAt: DateTime, updatedAt: DateTime, state: State[ReKeep], keeper: User, keep: Keep, uri: NormalizedURI, srcUser: User, srcKeep: Keep, attributionFactor: Int)

@json case class HelpRankInfo(uriId: Id[NormalizedURI], keepDiscoveryCount: Int, rekeepCount: Int)

@json case class UserKeepAttributionInfo(userId: Id[User], clickCount: Int, rekeepCount: Int, rekeepTotalCount: Int, uniqueKeepsClicked: Int = 0, totalClicks: Int = 0)