package com.keepit.common.social

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model.{ UserRepo, SocialUserInfoRepo, User }
import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.social._

class BasicUserRepo @Inject() (
    socialUserRepo: SocialUserInfoRepo,
    userRepo: UserRepo,
    basicUserExternalIdCache: BasicUserUserExternalIdCache,
    basicUserCache: BasicUserUserIdCache) {

  def load(userId: Id[User])(implicit session: RSession): BasicUser = {
    basicUserCache.getOrElse(BasicUserUserIdKey(userId)) {
      BasicUser.fromUser(userRepo.get(userId))
    }
  }

  def loadExternal(userId: ExternalId[User])(implicit session: RSession): BasicUser = {
    basicUserExternalIdCache.getOrElse(BasicUserUserExternalIdKey(userId)) {
      BasicUser.fromUser(userRepo.get(userId))
    }
  }

  def loadAll(userIds: Set[Id[User]])(implicit session: RSession): Map[Id[User], BasicUser] = {
    if (userIds.isEmpty) {
      Map.empty
    } else if (userIds.size == 1) {
      val userId = userIds.head
      Map(userId -> load(userId))
    } else {
      basicUserCache.bulkGetOrElse(userIds map BasicUserUserIdKey) { keys =>
        userRepo.getAllUsers(keys.map(_.userId).toSeq).map {
          case (userId, user) => BasicUserUserIdKey(userId) -> BasicUser.fromUser(user)
        }.toMap
      }.map { case (k, v) => k.userId -> v }
    }
  }

  def loadAllExternal(userIds: Set[ExternalId[User]])(implicit session: RSession): Map[ExternalId[User], BasicUser] = {
    if (userIds.isEmpty) {
      Map.empty
    } else if (userIds.size == 1) {
      val userId = userIds.head
      Map(userId -> loadExternal(userId))
    } else {
      basicUserExternalIdCache.bulkGetOrElse(userIds map BasicUserUserExternalIdKey) { keys =>
        userRepo.getAllUsersByExternalId(keys.map(_.userId).toSeq).map {
          case (userId, user) => BasicUserUserExternalIdKey(userId) -> BasicUser.fromUser(user)
        }.toMap
      }.map { case (k, v) => k.userId -> v }
    }
  }
}
