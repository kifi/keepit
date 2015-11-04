package com.keepit.model

import java.math.BigInteger
import java.security.SecureRandom

import com.keepit.common.db._
import com.keepit.common.mail.{ EmailAddressHash, EmailAddress }
import com.keepit.common.time._

import org.joda.time.DateTime
import play.api.mvc.PathBindable

case class EmailVerificationCode(value: String) extends AnyVal
object EmailVerificationCode {
  private lazy val random = new SecureRandom()
  def make = EmailVerificationCode(new BigInteger(128, random).toString(36))

  implicit def pathBinder(implicit stringBinder: PathBindable[String]) = new PathBindable[EmailVerificationCode] {
    override def bind(key: String, value: String): Either[String, EmailVerificationCode] =
      stringBinder.bind(key, value) match {
        case Right(code) => Right(EmailVerificationCode(code))
        case _ => Left("Unable to bind an EmailVerificationCode")
      }
    override def unbind(key: String, code: EmailVerificationCode): String = code.value
  }
}

case class UserEmailAddress(
    id: Option[Id[UserEmailAddress]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    state: State[UserEmailAddress] = UserEmailAddressStates.ACTIVE,
    address: EmailAddress,
    hash: EmailAddressHash,
    primary: Boolean = false,
    verifiedAt: Option[DateTime] = None,
    lastVerificationSent: Option[DateTime] = None,
    verificationCode: Option[EmailVerificationCode] = None,
    seq: SequenceNumber[UserEmailAddress] = SequenceNumber.ZERO) extends ModelWithState[UserEmailAddress] with ModelWithSeqNumber[UserEmailAddress] {
  def withId(id: Id[UserEmailAddress]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserEmailAddress]) = copy(state = state)
  def withVerificationCode(now: DateTime) = {
    this.copy(
      lastVerificationSent = Some(now),
      verificationCode = Some(EmailVerificationCode.make))
  }
  def withAddress(address: EmailAddress) = copy(address = address, hash = EmailAddressHash.hashEmailAddress(address))
  def clearVerificationCode = copy(lastVerificationSent = None, verificationCode = None)
  def verificationSent: Boolean = lastVerificationSent.isDefined && verificationCode.isDefined
  def verified: Boolean = (state == UserEmailAddressStates.ACTIVE) && verifiedAt.isDefined
  def sanitizedForDelete = copy(primary = false)
}

object UserEmailAddress {

  def create(userId: Id[User], address: EmailAddress): UserEmailAddress = {
    UserEmailAddress(
      userId = userId,
      address = address,
      hash = EmailAddressHash.hashEmailAddress(address)
    )
  }

  // primary: trueOrNull in db
  def applyFromDbRow(
    id: Option[Id[UserEmailAddress]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    state: State[UserEmailAddress] = UserEmailAddressStates.ACTIVE,
    address: EmailAddress,
    hash: EmailAddressHash,
    primaryOption: Option[Boolean],
    verifiedAt: Option[DateTime] = None,
    lastVerificationSent: Option[DateTime] = None,
    verificationCode: Option[EmailVerificationCode] = None,
    seq: SequenceNumber[UserEmailAddress] = SequenceNumber.ZERO): UserEmailAddress = {

    UserEmailAddress(
      id,
      createdAt,
      updatedAt,
      userId,
      state,
      address,
      hash,
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
      e.hash,
      if (e.primary) Some(true) else None,
      e.verifiedAt,
      e.lastVerificationSent,
      e.verificationCode,
      e.seq
    ))
  }
}

object UserEmailAddressStates extends States[UserEmailAddress]
