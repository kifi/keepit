package com.keepit.model

import java.math.BigInteger
import java.security.SecureRandom

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.mail.{EmailAddressHolder, EmailAddress}
import com.keepit.abook.{EmailParserUtils, EmailParser}
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key}
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration

case class UserEmailAddress (
  id: Option[Id[UserEmailAddress]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  state: State[UserEmailAddress] = EmailAddressStates.UNVERIFIED,
  address: String,
  verifiedAt: Option[DateTime] = None,
  lastVerificationSent: Option[DateTime] = None,
  verificationCode: Option[String] = None,
  seq: SequenceNumber[UserEmailAddress] = SequenceNumber.ZERO
) extends ModelWithState[UserEmailAddress] with EmailAddressHolder with ModelWithSeqNumber[UserEmailAddress] {
  def withId(id: Id[UserEmailAddress]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def sameAddress(otherAddress: String) = otherAddress == address
  def withState(state: State[UserEmailAddress]) = copy(state = state)
  def withVerificationCode(now: DateTime) = this.copy(
    lastVerificationSent = Some(now),
    verificationCode = Some(new BigInteger(128, EmailAddressObject.random).toString(36)))
  def verified: Boolean = state == EmailAddressStates.VERIFIED
  def isTestEmail() = EmailParserUtils.isTestEmail(address)
  def isFakeEmail() = EmailParserUtils.isFakeEmail(address) // +test
  def isAutoGenEmail() = EmailParserUtils.isAutoGenEmail(address)  // +autogen
}

object EmailAddressObject {
  lazy val random = new SecureRandom()
}

object EmailAddressStates {
  val VERIFIED = State[UserEmailAddress]("verified")
  val UNVERIFIED = State[UserEmailAddress]("unverified")
  val INACTIVE = State[UserEmailAddress]("inactive")
}

case class VerifiedEmailUserIdKey(address: String) extends Key[Id[User]] {
  override val version = 1
  val namespace = "user_id_by_verified_email"
  def toKey(): String = address
}

class VerifiedEmailUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[VerifiedEmailUserIdKey, Id[User]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(Id.format[User])
