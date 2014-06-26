package com.keepit.model

import java.math.BigInteger
import java.security.SecureRandom

import com.keepit.common.db._
import com.keepit.common.mail.{EmailAddress, EmailAddressParser}
import com.keepit.common.time._

import org.joda.time.DateTime

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
  def withVerificationCode(now: DateTime) = {
    this.copy(
      lastVerificationSent = Some(now),
      verificationCode = Some(new BigInteger(128, UserEmailAddress.random).toString(36)))
  }
  def verified: Boolean = state == UserEmailAddressStates.VERIFIED
  def isKifi: Boolean = parsed.exists { a => UserEmailAddress.kifiDomains.contains(a.domain) }
  def isTest: Boolean = parsed.exists { a => UserEmailAddress.testDomains.contains(a.domain) && (a.hasTagPrefix("test") || a.hasTagPrefix("utest")) }
  def isAutoGen: Boolean = isKifi && parsed.exists(_.hasTagPrefix("autogen"))
  def hasTag(tag: String): Boolean = parsed.exists(_.hasTag(tag))
  private lazy val parsed = EmailAddressParser.parseOpt(address.address)
}

object UserEmailAddress {
  lazy val random = new SecureRandom()
  val kifiDomains = Set("kifi.com", "42go.com")
  val testDomains = kifiDomains ++ Set("tfbnw.net", "mailinator.com")  // tfbnw.net ???

  def getExperiments(email: UserEmailAddress): Set[ExperimentType] = {
    (if (email.isAutoGen) {
      Set(ExperimentType.FAKE, ExperimentType.AUTO_GEN)
    } else if (email.isTest) {
      Set(ExperimentType.FAKE)
    } else {
      Set.empty
    }) ++ (if (email.hasTag("preview") || email.address.address == "casey@theverge.com") {
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
