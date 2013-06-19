package com.keepit.common.social


import com.keepit.common.db._
import com.keepit.model._

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key}
import scala.concurrent.duration.Duration

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
  override val version = 3
  val namespace = "basic_user_userid"
  def toKey(): String = userId.id.toString
}

class BasicUserUserIdCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[BasicUserUserIdKey, BasicUser](innermostPluginSettings, innerToOuterPluginSettings:_*)

// TODO(andrew): Invalidate cache. More tricky on this multi-object cache. Right now, the data doesn't change. When we go multi-network, it will.
// Also affected: CommentWithBasicUser
