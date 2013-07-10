package com.keepit.common.social

import com.keepit.common.db.Id
import com.keepit.model.{UserRepo, SocialUserInfoRepo, User}
import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.social.{BasicUserUserIdCache, BasicUserUserIdKey, BasicUser}

class BasicUserRepo @Inject() (socialUserRepo: SocialUserInfoRepo, userRepo: UserRepo, userCache: BasicUserUserIdCache){
  def load(userId: Id[User])(implicit session: RSession): BasicUser = userCache.getOrElse(BasicUserUserIdKey(userId)) {
    BasicUser.fromUser(userRepo.get(userId))
  }
}
