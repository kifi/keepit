package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.{ UserConnectionRepo, User }

class UserMutualConnectionsCommander @Inject() (
    userConnectionRepo: UserConnectionRepo,
    db: Database) {

  def getMutualFriends(user1Id: Id[User], user2Id: Id[User]): Set[Id[User]] = {
    val (user1FriendIds, user2FriendIds) = db.readOnlyReplica { implicit session =>
      (userConnectionRepo.getConnectedUsers(user1Id), userConnectionRepo.getConnectedUsers(user2Id))
    }
    user1FriendIds intersect user2FriendIds
  }

}
