package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.social.BasicUser
import com.keepit.social.BasicUser.{ mapUserIdToInt, mapUserIdToBasicUser }

case class FriendRecommendations(
  basicUsers: Map[Id[User], BasicUser],
  userConnectionCounts: Map[Id[User], Int],
  recommendedUsers: Seq[Id[User]],
  mutualFriends: Map[Id[User], Seq[Id[User]]],
  mutualLibraries: Map[Id[User], Seq[Library]])
