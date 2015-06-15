package com.keepit.model

import com.keepit.common.cache.{ Key, CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.service.IpAddress
import com.keepit.common.time.DateTimeJsonFormat
import com.keepit.model.UserIpAddress._
import org.joda.time.DateTime

import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class UserIpAddress(
    id: Option[Id[UserIpAddress]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[UserIpAddress],
    userId: Id[User],
    ipAddress: IpAddress,
    // TODO: Turn agentType into an Enum?
    agentType: String) extends ModelWithState[UserIpAddress] {

  def withId(id: Id[UserIpAddress]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object UserIpAddress {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[UserIpAddress]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[UserIpAddress]) and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'ipAddress).format[IpAddress] and
    (__ \ 'agentType).format[String]
  )(UserIpAddress.apply, unlift(UserIpAddress.unapply))
}

object UserIpAddressStates extends States[UserIpAddress] {}

case class UserIpAddressKey(id: Id[User]) extends Key[UserIpAddress] {
  override val version = 1
  val namespace = "ip_address_by_user_id"
  def toKey(): String = id.id.toString
}

class UserIpAddressCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserIpAddressKey, UserIpAddress](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
