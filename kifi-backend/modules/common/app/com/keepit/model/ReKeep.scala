package com.keepit.model

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

object ReKeepStates extends States[ReKeep]

case class RichReKeep(id: Option[Id[ReKeep]], createdAt: DateTime, updatedAt: DateTime, state: State[ReKeep], keeper: User, keep: Keep, uri: NormalizedURI, srcUser: User, srcKeep: Keep, attributionFactor: Int)

case class HelpRankInfo(uriId: Id[NormalizedURI], keepDiscoveryCount: Int, rekeepCount: Int)

object HelpRankInfo {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  implicit val format = (
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'keepDiscoveryCount).format[Int] and
    (__ \ 'rekeepCount).format[Int]
  )(HelpRankInfo.apply _, unlift(HelpRankInfo.unapply))
}