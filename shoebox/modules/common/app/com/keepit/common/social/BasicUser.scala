package com.keepit.common.social

import scala.concurrent.duration._

import com.google.inject.Inject
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCache, FortyTwoCachePlugin, Key}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.serializer.Serializer

case class BasicUser(
  externalId: ExternalId[User],
  firstName: String,
  lastName: String,
  networkIds: Map[SocialNetworkType, SocialId],
  pictureName: String)

object BasicUser {
  implicit val userExternalIdFormat = ExternalId.format[User]
  implicit val basicUserFormat = (
      (__ \ 'id).format[ExternalId[User]] and
      (__ \ 'firstName).format[String] and
      (__ \ 'lastName).format[String] and
      (__ \ 'networkIds).format[Map[String, String]].inmap[Map[SocialNetworkType,SocialId]](
        _.map { case (netStr, idStr) => SocialNetworkType(netStr) -> SocialId(idStr) }.toMap,
        _.map { case (network, id) => network.name -> id.id }.toMap
      ) and
      (__ \ 'pictureName).format[String]
  )(BasicUser.apply, unlift(BasicUser.unapply))
}

case class BasicUserUserIdKey(userId: Id[User]) extends Key[BasicUser] {
  override val version = 2
  val namespace = "basic_user_userid"
  def toKey(): String = userId.id.toString
}

class BasicUserUserIdCache @Inject() (repo: FortyTwoCachePlugin)
  extends JsonCacheImpl[BasicUserUserIdKey, BasicUser]((repo, 7 days))
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
