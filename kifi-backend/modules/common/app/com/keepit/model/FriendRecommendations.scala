package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.social.BasicUser
import com.keepit.social.BasicUser.{ mapUserIdToInt, mapUserIdToBasicUser }

case class FriendRecommendations(basicUsers: Map[Id[User], BasicUser],
  mutualFriendConnectionCounts: Map[Id[User], Int],
  recommendedUsers: Seq[Id[User]],
  mutualFriends: Map[Id[User], Seq[Id[User]]])

object FriendRecommendations {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val format: Format[FriendRecommendations] = (
    (__ \ 'basicUsers).format[Map[Id[User], BasicUser]] and
    (__ \ 'mutualFriendConnectionCounts).format[Map[Id[User], Int]] and
    (__ \ 'recommendedUsers).format[Seq[Id[User]]] and
    (__ \ 'mutualFriends).format[Map[Id[User], Seq[Id[User]]]]
  )(FriendRecommendations.apply _, unlift(FriendRecommendations.unapply))
}
