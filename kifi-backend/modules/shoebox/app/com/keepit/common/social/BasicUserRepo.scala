package com.keepit.common.social

import com.keepit.common.db.Id
import com.keepit.model.{ UserRepo, SocialUserInfoRepo, User }
import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.social.{ BasicUserUserIdCache, BasicUserUserIdKey, BasicUser }

class BasicUserRepo @Inject() (socialUserRepo: SocialUserInfoRepo, userRepo: UserRepo, basicUserCache: BasicUserUserIdCache) {
  def load(userId: Id[User])(implicit session: RSession): BasicUser = {
    basicUserCache.getOrElse(BasicUserUserIdKey(userId)) {
      BasicUser.fromUser(userRepo.get(userId))
    }
  }

  def loadAll(userIds: Set[Id[User]])(implicit session: RSession): Map[Id[User], BasicUser] = {
    basicUserCache.bulkGetOrElse(userIds map BasicUserUserIdKey) { keys =>
      userRepo.getUsers(keys.map(_.userId).toSeq).map {
        case (userId, user) => BasicUserUserIdKey(userId) -> BasicUser.fromUser(user)
      }.toMap
    }.map { case (k, v) => k.userId -> v }
  }
}
