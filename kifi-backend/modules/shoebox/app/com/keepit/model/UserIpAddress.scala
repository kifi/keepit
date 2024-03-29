package com.keepit.model

import com.keepit.common.cache.{ Key, CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.service.IpAddress
import com.keepit.common.time._
import com.keepit.model.UserIpAddress._
import com.keepit.shoebox.model.IngestableUserIpAddress
import org.joda.time.DateTime

import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class UserIpAddress(
    id: Option[Id[UserIpAddress]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[UserIpAddress] = UserIpAddressStates.ACTIVE,
    userId: Id[User],
    ipAddress: IpAddress,
    // TODO: Turn agentType into an Enum?
    agentType: String,
    seq: SequenceNumber[UserIpAddress] = SequenceNumber.ZERO) extends ModelWithState[UserIpAddress] with ModelWithSeqNumber[UserIpAddress] {

  def withId(id: Id[UserIpAddress]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  implicit def toIngestableUserIpAddressSeq(seqNum: SequenceNumber[UserIpAddress]): SequenceNumber[IngestableUserIpAddress] = seqNum.copy()
  def toIngestableUserIpAddress = IngestableUserIpAddress(userId, ipAddress, updatedAt, seq)
}

object UserIpAddress {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[UserIpAddress]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[UserIpAddress]) and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'ipAddress).format[IpAddress] and
    (__ \ 'agentType).format[String] and
    (__ \ 'seqNum).format(SequenceNumber.format[UserIpAddress])
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
