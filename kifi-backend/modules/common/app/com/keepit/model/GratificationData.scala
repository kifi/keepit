package com.keepit.model

import com.keepit.common.db.Id
import com.kifi.macros.json
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class CountData[T](totalCount: Int, countById: Map[Id[T], Int]) {
  val sortedCountById = countById.toList.sortWith {
    _._2 > _._2
  }
}

object CountData {
  implicit def format[T] = (
    (__ \ 'totalCount).format[Int] and
    (__ \ 'countById).format[Map[Id[T], Int]]
  )(CountData.apply, unlift(CountData.unapply))
}

@json
case class GratificationData(
    userId: Id[User],
    libraryViews: CountData[Library],
    keepViews: CountData[Keep],
    rekeeps: CountData[Keep],
    libraryFollows: CountData[Library] = CountData[Library](0, Map.empty)) {
  import GratificationData._
  def isEligible = libraryViews.totalCount >= MIN_LIB_VIEWS || keepViews.totalCount >= MIN_KEEP_VIEWS ||
    rekeeps.totalCount >= MIN_REKEEPS || libraryFollows.totalCount >= MIN_FOLLOWS
}

object GratificationData {
  val MIN_KEEP_VIEWS = 5
  val MIN_LIB_VIEWS = 5
  val MIN_REKEEPS = 1
  val MIN_FOLLOWS = 1
  val LIST_LIMIT = 7
}
