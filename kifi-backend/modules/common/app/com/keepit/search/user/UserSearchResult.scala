package com.keepit.search.user

import com.keepit.social.BasicUser
import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class UserHit(id: Id[User], basicUser: BasicUser, isFriend: Boolean = false)
case class UserSearchResult(hits: Array[UserHit], context: String)
case class UserSearchRequest(userId: Option[Id[User]], queryText: String, maxHits: Int, context: String, filter: String) // userId is the id of the user who sends user search request

object UserHit {
  implicit val userIdFormat = Id.format[User]
  implicit val basicUserFormat = BasicUser.basicUserFormat
  implicit val userHitFormat = (
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'basicUser).format[BasicUser] and
    (__ \ 'isFriend).format[Boolean]
  )(UserHit.apply, unlift(UserHit.unapply))
}

object UserSearchResult {
  implicit val userHitFormat = UserHit.userHitFormat
  implicit val userSearchResultFormat = (
    (__ \ 'hits).format[Array[UserHit]] and
    (__ \ 'context).format[String]
  )(UserSearchResult.apply, unlift(UserSearchResult.unapply))
}

object UserSearchRequest {
  implicit val userIdFormat = Id.format[User]
  implicit val userSearchRequestFormat = (
    (__ \ 'userId).format[Option[Id[User]]] and
    (__ \ 'queryText).format[String] and
    (__ \ 'maxHits).format[Int] and
    (__ \ 'context).format[String] and
    (__ \ 'filter).format[String]
  )(UserSearchRequest.apply, unlift(UserSearchRequest.unapply))
}

