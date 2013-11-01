package com.keepit.search.user

import com.keepit.social.BasicUser
import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class UserHit(id: Id[User], basicUser: BasicUser)
case class UserSearchResult(hits: Array[UserHit], context: String)

object UserHit {
  implicit val userIdFormat = Id.format[User]
  implicit val basicUserFormat = BasicUser.basicUserFormat
  implicit val userHitFormat = (
    (__ \'userId).format[Id[User]] and
    (__ \'basicUser).format[BasicUser]
  )(UserHit.apply, unlift(UserHit.unapply))
}

object UserSearchResult {
  implicit val userHitFormat = UserHit.userHitFormat
  implicit val userSearchResultFormat = (
    (__ \'hits).format[Array[UserHit]] and
    (__ \'context).format[String]
  )(UserSearchResult.apply, unlift(UserSearchResult.unapply))
}

