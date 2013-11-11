package com.keepit.model

import java.math.BigInteger
import java.security.SecureRandom

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.mail.EmailAddressHolder

case class EmailAddress (
  id: Option[Id[EmailAddress]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  state: State[EmailAddress] = EmailAddressStates.UNVERIFIED,
  address: String,
  verifiedAt: Option[DateTime] = None,
  lastVerificationSent: Option[DateTime] = None,
  verificationCode: Option[String] = None
) extends Model[EmailAddress] with EmailAddressHolder {
  def withId(id: Id[EmailAddress]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def sameAddress(otherAddress: String) = otherAddress == address
  def withState(state: State[EmailAddress]) = copy(state = state)
  def withVerificationCode(now: DateTime) = this.copy(
    lastVerificationSent = Some(now),
    verificationCode = Some(new BigInteger(128, EmailAddressObject.random).toString(36)))
  def verified: Boolean = state == EmailAddressStates.VERIFIED
  def isTestEmail(): Boolean = EmailAddressObject.TestEmailPattern.pattern.matcher(address).matches
}

object EmailAddressObject {
  lazy val random = new SecureRandom()
  val TestEmailPattern = """^(.*\+test.*@42go.com)$""".r
}

object EmailAddressStates {
  val VERIFIED = State[EmailAddress]("verified")
  val UNVERIFIED = State[EmailAddress]("unverified")
  val INACTIVE = State[EmailAddress]("inactive")
}
