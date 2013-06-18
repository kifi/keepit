package com.keepit.common.social

import com.keepit.common.db.Id
import com.keepit.model.{UserRepo, SocialUserInfoRepo, User}
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key}
import scala.concurrent.duration.Duration
import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.RSession

case class BasicUserUserIdKey(userId: Id[User]) extends Key[BasicUser] {
  override val version = 3
  val namespace = "basic_user_userid"
  def toKey(): String = userId.id.toString
}

class BasicUserUserIdCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[BasicUserUserIdKey, BasicUser](innermostPluginSettings, innerToOuterPluginSettings:_*)

// TODO(andrew): Invalidate cache. More tricky on this multi-object cache. Right now, the data doesn't change. When we go multi-network, it will.
// Also affected: CommentWithBasicUser

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