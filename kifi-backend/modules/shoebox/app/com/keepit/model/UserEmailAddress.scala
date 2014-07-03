package com.keepit.model

import java.math.BigInteger
import java.security.SecureRandom

import com.keepit.common.db._
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.model.ExperimentType.{AUTO_GEN, FAKE, GUIDE}

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
}

object UserEmailAddress {
  lazy val random = new SecureRandom()
  val kifiDomains = Set("kifi.com", "42go.com")
  val testDomains = kifiDomains ++ Set("tfbnw.net", "mailinator.com")  // tfbnw.net ???
  val tagRe = """(?<=\+)[^@+]*(?=(?:\+|$))""".r

  def getExperiments(email: UserEmailAddress): Set[ExperimentType] = {
    val Array(local, host) = email.address.address.split('@')
    val tags = tagRe.findAllIn(local).toSet
    val exps: Set[ExperimentType] = if (kifiDomains.contains(host) && tags.exists(_.startsWith("autogen"))) {
      Set(FAKE, AUTO_GEN)
    } else if (testDomains.contains(host) && tags.exists { t => t.startsWith("test") || t.startsWith("utest") }) {
      Set(FAKE)
    } else {
      Set.empty
    }
    if (exps.contains(FAKE)) exps else exps + GUIDE  // GUIDE is an A/B test for all new signups
  }
}

object UserEmailAddressStates {
  val VERIFIED = State[UserEmailAddress]("verified")
  val UNVERIFIED = State[UserEmailAddress]("unverified")
  val INACTIVE = State[UserEmailAddress]("inactive")
}
