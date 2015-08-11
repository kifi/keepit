package com.keepit.model

import java.math.BigInteger
import java.security.SecureRandom

import com.keepit.common.db._
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.model.UserExperimentType.{ AUTO_GEN, FAKE }

import org.joda.time.DateTime

case class UserEmailAddress(
    id: Option[Id[UserEmailAddress]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    state: State[UserEmailAddress] = UserEmailAddressStates.ACTIVE,
    address: EmailAddress,
    primary: Boolean = false,
    verifiedAt: Option[DateTime] = None,
    lastVerificationSent: Option[DateTime] = None,
    verificationCode: Option[String] = None,
    seq: SequenceNumber[UserEmailAddress] = SequenceNumber.ZERO) extends ModelWithState[UserEmailAddress] with ModelWithSeqNumber[UserEmailAddress] {
  def withId(id: Id[UserEmailAddress]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserEmailAddress]) = copy(state = state)
  def withVerificationCode(now: DateTime) = {
    this.copy(
      lastVerificationSent = Some(now),
      verificationCode = Some(new BigInteger(128, UserEmailAddress.random).toString(36)))
  }
  def clearVerificationCode = copy(lastVerificationSent = None, verificationCode = None)
  def verificationSent: Boolean = lastVerificationSent.isDefined && verificationCode.isDefined
  def verified: Boolean = (state == UserEmailAddressStates.ACTIVE) && verifiedAt.isDefined
  def sanitizedForDelete = copy(primary = false)
}

object UserEmailAddress {
  private lazy val random = new SecureRandom()

  // primary: trueOrNull in db
  def applyFromDbRow(
    id: Option[Id[UserEmailAddress]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    state: State[UserEmailAddress] = UserEmailAddressStates.ACTIVE,
    address: EmailAddress,
    primaryOption: Option[Boolean],
    verifiedAt: Option[DateTime] = None,
    lastVerificationSent: Option[DateTime] = None,
    verificationCode: Option[String] = None,
    seq: SequenceNumber[UserEmailAddress] = SequenceNumber.ZERO): UserEmailAddress = {

    UserEmailAddress(
      id,
      createdAt,
      updatedAt,
      userId,
      state,
      address,
      primaryOption.contains(true),
      verifiedAt,
      lastVerificationSent,
      verificationCode,
      seq
    )
  }

  def unapplyToDbRow(e: UserEmailAddress) = {
    Some((
      e.id,
      e.createdAt,
      e.updatedAt,
      e.userId,
      e.state,
      e.address,
      if (e.primary) Some(true) else None,
      e.verifiedAt,
      e.lastVerificationSent,
      e.verificationCode,
      e.seq
    ))
  }
}

object UserEmailAddressStates extends States[UserEmailAddress]
