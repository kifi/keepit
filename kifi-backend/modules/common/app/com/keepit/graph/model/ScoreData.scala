package com.keepit.graph.model

import com.keepit.common.db.{Id}
import com.keepit.model.{NormalizedURI, User}
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class UserScoreData(userId: Id[User], num: Int)

object UserScoreData {
  implicit val format = (
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'number).format[Int]
  )(UserScoreData.apply, unlift(UserScoreData.unapply))
}

case class UriScoreData(uriId: Id[NormalizedURI], num: Int)

object UriScoreData {
  implicit val format = (
      (__ \ 'uriId).format(Id.format[NormalizedURI]) and
      (__ \ 'number).format[Int]
    )(UriScoreData.apply, unlift(UriScoreData.unapply))
}
