package com.keepit.search.user

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.social.BasicUser
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class DeprecatedUserHit(id: Id[User], basicUser: BasicUser, isFriend: Boolean = false)
case class DeprecatedUserSearchResult(hits: Array[DeprecatedUserHit], context: String)
case class DeprecatedUserSearchRequest(userId: Option[Id[User]], queryText: String, maxHits: Int, context: String, filter: String) // userId is the id of the user who sends user search request

object DeprecatedUserHit {
  private implicit val userIdFormat = Id.format[User]
  private implicit val basicUserFormat = BasicUser.format
  implicit val format = (
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'basicUser).format[BasicUser] and
    (__ \ 'isFriend).format[Boolean]
  )(DeprecatedUserHit.apply, unlift(DeprecatedUserHit.unapply))
}

object DeprecatedUserSearchResult {
  private implicit val userHitFormat = DeprecatedUserHit.format
  implicit val format = (
    (__ \ 'hits).format[Array[DeprecatedUserHit]] and
    (__ \ 'context).format[String]
  )(DeprecatedUserSearchResult.apply, unlift(DeprecatedUserSearchResult.unapply))
}

object DeprecatedUserSearchRequest {
  private implicit val userIdFormat = Id.format[User]
  implicit val format = (
    (__ \ 'userId).format[Option[Id[User]]] and
    (__ \ 'queryText).format[String] and
    (__ \ 'maxHits).format[Int] and
    (__ \ 'context).format[String] and
    (__ \ 'filter).format[String]
  )(DeprecatedUserSearchRequest.apply, unlift(DeprecatedUserSearchRequest.unapply))
}
