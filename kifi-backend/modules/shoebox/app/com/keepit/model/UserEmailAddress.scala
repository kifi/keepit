package com.keepit.model

import java.math.BigInteger
import java.security.SecureRandom

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.mail.EmailAddress
import com.keepit.abook.EmailParserUtils
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key}
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration

case class UserEmailAddress (
  id: Option[Id[UserEmailAddress]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  state: State[UserEmailAddress] = UserEmailAddressStates.UNVERIFIED,
  address: EmailAddress,
  verifiedAt: Option[DateTime] = None,
  lastVerificationSent: Option[DateTime] = None,
  verificationCode: Option[String] = None,
  seq: SequenceNumber[UserEmailAddress] = SequenceNumber.ZERO
) extends ModelWithState[UserEmailAddress] with ModelWithSeqNumber[UserEmailAddress] {
  def withId(id: Id[UserEmailAddress]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserEmailAddress]) = copy(state = state)
  def withVerificationCode(now: DateTime) = this.copy(
    lastVerificationSent = Some(now),
    verificationCode = Some(new BigInteger(128, UserEmailAddress.random).toString(36)))
  def verified: Boolean = state == UserEmailAddressStates.VERIFIED
  def isTestEmail() = EmailParserUtils.isTestEmail(address.address)
  def isFakeEmail() = EmailParserUtils.isFakeEmail(address.address) // +test
  def isAutoGenEmail() = EmailParserUtils.isAutoGenEmail(address.address)  // +autogen
}

object UserEmailAddress {
  lazy val random = new SecureRandom()

  def getTestExperiments(email: UserEmailAddress): Set[ExperimentType] = {
    if (email.isTestEmail()) {
      if (email.isAutoGenEmail()) Set(ExperimentType.FAKE, ExperimentType.AUTO_GEN)
      else Set(ExperimentType.FAKE)
    } else
      Set.empty
  }
}

object UserEmailAddressStates {
  val VERIFIED = State[UserEmailAddress]("verified")
  val UNVERIFIED = State[UserEmailAddress]("unverified")
  val INACTIVE = State[UserEmailAddress]("inactive")
}
