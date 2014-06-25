package com.keepit.model

import java.math.BigInteger
import java.security.SecureRandom

import com.keepit.abook.EmailParser
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key}
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._

import org.joda.time.DateTime
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
  def isTagged(tag: String): Boolean = parsed.exists(_.isTagged(tag))
  def isTest: Boolean = parsed.exists(_.isTest) // +test or +utest
  def isAutoGen: Boolean = parsed.exists(_.isAutoGen)  // +autogen
  private lazy val parsed = EmailParser.parseOpt(address.address)
}

object UserEmailAddress {
  lazy val random = new SecureRandom()

  def getExperiments(email: UserEmailAddress): Set[ExperimentType] = {
    (if (email.isAutoGen) {
      Set(ExperimentType.FAKE, ExperimentType.AUTO_GEN)
    } else if (email.isTest) {
      Set(ExperimentType.FAKE)
    } else {
      Set.empty
    }) ++ (if (email.isTagged("preview")) {
      Set(ExperimentType.KIFI_BLACK)
    } else {
      Set.empty
    })
  }
}

object UserEmailAddressStates {
  val VERIFIED = State[UserEmailAddress]("verified")
  val UNVERIFIED = State[UserEmailAddress]("unverified")
  val INACTIVE = State[UserEmailAddress]("inactive")
}
