package com.keepit.social

import scala.concurrent.duration.Duration

import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, Key}
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class BasicUser(
  externalId: ExternalId[User],
  firstName: String,
  lastName: String,
  pictureName: String)

object BasicUser {
  implicit val userExternalIdFormat = ExternalId.format[User]
  implicit val basicUserFormat = (
      (__ \ 'id).format[ExternalId[User]] and
      (__ \ 'firstName).format[String] and
      (__ \ 'lastName).format[String] and
      (__ \ 'pictureName).format[String]
  )(BasicUser.apply, unlift(BasicUser.unapply))

  def fromUser(user: User): BasicUser = {
    BasicUser(
      externalId = user.externalId,
      firstName = user.firstName,
      lastName = user.lastName,
      pictureName = user.pictureName.map(_+ ".jpg").getOrElse("0.jpg") // need support for default image
    )
  }
}

case class BasicUserUserIdKey(userId: Id[User]) extends Key[BasicUser] {
  override val version = 5
  val namespace = "basic_user_userid"
  def toKey(): String = userId.id.toString
}

class BasicUserUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[BasicUserUserIdKey, BasicUser](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)
