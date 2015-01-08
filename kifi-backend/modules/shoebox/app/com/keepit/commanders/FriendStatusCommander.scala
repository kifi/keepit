package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.social.BasicUser

class FriendStatusCommander @Inject() (
    userConnectionRepo: UserConnectionRepo,
    friendRequestRepo: FriendRequestRepo) extends Logging {

  // Augments a BasicUser with its friend status with respect to userId.
  def augmentWithFriendStatus(userId: Id[User], basicUserId: Id[User], basicUser: BasicUser)(implicit session: RSession): BasicUserWithFriendStatus = {
    if (userId == basicUserId) {
      BasicUserWithFriendStatus.fromWithoutFriendStatus(basicUser)
    } else if (userConnectionRepo.areConnected(userId, basicUserId)) {
      BasicUserWithFriendStatus.from(basicUser, true)
    } else {
      augmentNonFriendWithFriendStatus(userId, basicUserId, basicUser)
    }
  }

  // Augments a map of BasicUsers with their friend status with respect to userId.
  def augmentWithFriendStatus(userId: Id[User], basicUsers: Map[Id[User], BasicUser])(implicit session: RSession): Map[Id[User], BasicUserWithFriendStatus] = {
    val otherUserIds = basicUsers.keySet - userId
    if (otherUserIds.isEmpty) {
      basicUsers mapValues BasicUserWithFriendStatus.fromWithoutFriendStatus
    } else if (otherUserIds.size == 1) {
      basicUsers.map {
        case (id, user) =>
          (id, augmentWithFriendStatus(userId, id, user))
      }
    } else {
      val friendIds = userConnectionRepo.getConnectedUsers(userId)
      val nonFriendUserIds = otherUserIds -- friendIds
      if (nonFriendUserIds.size <= 1) {
        basicUsers.map {
          case (id, user) =>
            if (nonFriendUserIds.contains(id)) {
              (id, augmentNonFriendWithFriendStatus(userId, id, user))
            } else {
              (id, BasicUserWithFriendStatus.from(user, true))
            }
        }
      } else {
        val reqsSent = friendRequestRepo.getBySender(userId, Set(FriendRequestStates.ACTIVE, FriendRequestStates.IGNORED))
        val reqsReceived = friendRequestRepo.getByRecipient(userId, Set(FriendRequestStates.ACTIVE))
        basicUsers.map {
          case (id, user) =>
            val reqSent = reqsSent.find(_.recipientId == id)
            val reqReceived = reqsReceived.find(_.senderId == id)
            if (friendIds.contains(id)) {
              (id, BasicUserWithFriendStatus.from(user, true))
            } else if (reqSent.isDefined) {
              (id, BasicUserWithFriendStatus.fromWithRequestSentAt(user, reqSent.get.createdAt))
            } else if (reqReceived.isDefined) {
              (id, BasicUserWithFriendStatus.fromWithRequestReceivedAt(user, reqReceived.get.createdAt))
            } else {
              (id, BasicUserWithFriendStatus.from(user, false))
            }
        }
      }
    }
  }

  private def augmentNonFriendWithFriendStatus(userId: Id[User], basicUserId: Id[User], basicUser: BasicUser)(implicit session: RSession): BasicUserWithFriendStatus = {
    friendRequestRepo.getBySenderAndRecipient(basicUserId, userId, Set(FriendRequestStates.ACTIVE)) map { req =>
      BasicUserWithFriendStatus.fromWithRequestReceivedAt(basicUser, req.createdAt)
    } getOrElse {
      friendRequestRepo.getBySenderAndRecipient(userId, basicUserId, Set(FriendRequestStates.ACTIVE, FriendRequestStates.IGNORED)) map { req =>
        BasicUserWithFriendStatus.fromWithRequestSentAt(basicUser, req.createdAt)
      } getOrElse {
        BasicUserWithFriendStatus.from(basicUser, false)
      }
    }
  }

}
