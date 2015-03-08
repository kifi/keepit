package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.social.BasicUser

class FriendStatusCommander @Inject() (
    userConnectionRepo: UserConnectionRepo,
    friendRequestRepo: FriendRequestRepo,
    searchFriendRepo: SearchFriendRepo) extends Logging {

  // Augments a BasicUser with its friend status with respect to userId.
  def augmentUser(userId: Id[User], basicUserId: Id[User], basicUser: BasicUser)(implicit session: RSession): BasicUserWithFriendStatus = {
    if (userId == basicUserId) {
      BasicUserWithFriendStatus.fromWithoutFriendStatus(basicUser)
    } else if (userConnectionRepo.areConnected(userId, basicUserId)) {
      augmentFriend(userId, basicUserId, basicUser)
    } else {
      augmentNonFriend(userId, basicUserId, basicUser)
    }
  }

  // Augments a map of BasicUsers with their friend status with respect to userId.
  def augmentUsers(userId: Id[User], basicUsers: Map[Id[User], BasicUser])(implicit session: RSession): Map[Id[User], BasicUserWithFriendStatus] = {
    val size = basicUsers.size
    if (size == 1 || (size == 2 && basicUsers.contains(userId))) {
      basicUsers.map {
        case (id, user) =>
          (id, augmentUser(userId, id, user))
      }
    } else {
      augmentUsers(userId, basicUsers, userConnectionRepo.getConnectedUsers(userId))
    }
  }

  // Augments a map of BasicUsers with their friend status with respect to `userId`, given a `Set[Id[User]]` that can be used to check for a friendship with `userId`.
  def augmentUsers(userId: Id[User], basicUsers: Map[Id[User], BasicUser], friendIds: Set[Id[User]])(implicit session: RSession): Map[Id[User], BasicUserWithFriendStatus] = {
    val groups = basicUsers.groupBy {
      case (id, _) =>
        if (friendIds.contains(id)) "friend" else if (id == userId) "self" else "other"
    }
    augmentFriends(userId, groups.getOrElse("friend", Map.empty)) ++
      augmentNonFriends(userId, groups.getOrElse("other", Map.empty)) ++
      groups.getOrElse("self", Map.empty).mapValues(BasicUserWithFriendStatus.fromWithoutFriendStatus)
  }

  // Augments a BasicUser already known *not* to be friends with userId.
  private def augmentFriend(userId: Id[User], friendId: Id[User], friend: BasicUser)(implicit session: RSession): BasicUserWithFriendStatus = {
    BasicUserWithFriendStatus.from(friend, true, searchFriendRepo.getSearchFriends(userId).contains(friendId))
  }

  // Augments a map of BasicUsers already known to be friends with userId.
  private def augmentFriends(userId: Id[User], friends: Map[Id[User], BasicUser])(implicit session: RSession): Map[Id[User], BasicUserWithFriendStatus] = {
    val searchFriends = searchFriendRepo.getSearchFriends(userId)
    friends.map { case (friendId, friend) => friendId -> BasicUserWithFriendStatus.from(friend, true, searchFriends.contains(friendId)) }
  }

  // Augments a BasicUser already known *not* to be friends with userId.
  private def augmentNonFriend(userId: Id[User], nonFriendId: Id[User], nonFriend: BasicUser)(implicit session: RSession): BasicUserWithFriendStatus = {
    friendRequestRepo.getBySenderAndRecipient(nonFriendId, userId, Set(FriendRequestStates.ACTIVE)) map { req =>
      BasicUserWithFriendStatus.fromWithRequestReceivedAt(nonFriend, req.createdAt)
    } getOrElse {
      friendRequestRepo.getBySenderAndRecipient(userId, nonFriendId, Set(FriendRequestStates.ACTIVE, FriendRequestStates.IGNORED)) map { req =>
        BasicUserWithFriendStatus.fromWithRequestSentAt(nonFriend, req.createdAt)
      } getOrElse {
        BasicUserWithFriendStatus.from(nonFriend, false, false)
      }
    }
  }

  // Augments a map of BasicUsers already known *not* to be friends with userId.
  private def augmentNonFriends(userId: Id[User], nonFriends: Map[Id[User], BasicUser])(implicit session: RSession): Map[Id[User], BasicUserWithFriendStatus] = {
    if (nonFriends.size <= 1) {
      nonFriends.map {
        case (id, user) =>
          (id, augmentNonFriend(userId, id, user))
      }
    } else {
      val reqsSent = friendRequestRepo.getBySender(userId, Set(FriendRequestStates.ACTIVE, FriendRequestStates.IGNORED))
      val reqsReceived = friendRequestRepo.getByRecipient(userId, Set(FriendRequestStates.ACTIVE))
      nonFriends.map {
        case (id, user) =>
          reqsSent.find(_.recipientId == id).map { reqSent =>
            id -> BasicUserWithFriendStatus.fromWithRequestSentAt(user, reqSent.createdAt)
          } orElse reqsReceived.find(_.senderId == id).map { reqReceived =>
            id -> BasicUserWithFriendStatus.fromWithRequestReceivedAt(user, reqReceived.createdAt)
          } getOrElse {
            id -> BasicUserWithFriendStatus.from(user, false, false)
          }
      }
    }
  }

}
