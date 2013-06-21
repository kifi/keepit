package com.keepit.common.social

import com.keepit.common.db.Id
import com.keepit.model.{UserRepo, SocialUserInfoRepo, User}
import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession

class BasicUserRepo @Inject() (socialUserRepo: SocialUserInfoRepo, userRepo: UserRepo, userCache: BasicUserUserIdCache){
  def load(userId: Id[User])(implicit session: RSession): BasicUser = userCache.getOrElse(BasicUserUserIdKey(userId)) {
    val user = userRepo.get(userId)
    val socialUserInfos = socialUserRepo.getByUser(user.id.get)
    BasicUser(
      externalId = user.externalId,
      firstName = user.firstName,
      lastName = user.lastName,
      networkIds = socialUserInfos.map { su => su.networkType -> su.socialId }.toMap,
      pictureName = "0.jpg" // TODO: when we have multiple picture IDs make sure we change this
    )
  }
}