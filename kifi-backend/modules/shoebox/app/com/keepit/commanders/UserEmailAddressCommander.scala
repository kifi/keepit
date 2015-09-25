package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.emails.EmailConfirmationSender
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE, currentDateTime }
import com.keepit.heimdal.{ HeimdalServiceClient, ContextStringData }
import com.keepit.model._
import com.keepit.common.core._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Failure, Try }

class UnavailableEmailAddressException(email: UserEmailAddress, requesterId: Id[User]) extends Exception(s"Email address ${email.address} has already been verified by user ${email.userId}, cannot be claimed by user $requesterId.")
class LastEmailAddressException(email: UserEmailAddress) extends Exception(s"${email.address} is the last email address of user ${email.userId}, it cannot be removed.")
class LastVerifiedEmailAddressException(email: UserEmailAddress) extends Exception(s"${email.address} is the last verified email address of user ${email.userId}, it cannot be removed.")
class PrimaryEmailAddressException(email: UserEmailAddress) extends Exception(s"${email.address} is the primary email address of user ${email.userId}, it cannot be removed.")

@ImplementedBy(classOf[UserEmailAddressCommanderImpl])
trait UserEmailAddressCommander {
  def sendVerificationEmail(emailAddress: UserEmailAddress): Future[Unit]
  def verifyEmailAddress(verificationCode: String)(implicit session: RWSession): Option[(UserEmailAddress, Boolean)]
  def intern(userId: Id[User], address: EmailAddress, verified: Boolean = false)(implicit session: RWSession): Try[(UserEmailAddress, Boolean)]
  def saveAsVerified(emailAddress: UserEmailAddress)(implicit session: RWSession): UserEmailAddress
  def setAsPrimaryEmail(emailAddress: UserEmailAddress)(implicit session: RWSession): Unit
  def deactivate(emailAddress: UserEmailAddress, force: Boolean = false)(implicit session: RWSession): Try[Unit]

  def addEmail(userId: Id[User], address: EmailAddress): Either[String, Unit]
}

@Singleton
class UserEmailAddressCommanderImpl @Inject() (db: Database,
    userEmailAddressRepo: UserEmailAddressRepo,
    userValueRepo: UserValueRepo,
    userRepo: UserRepo,
    pendingInviteCommander: PendingInviteCommander,
    heimdalClient: HeimdalServiceClient,
    emailConfirmationSender: EmailConfirmationSender,
    clock: Clock,
    implicit val executionContext: ExecutionContext) extends UserEmailAddressCommander with Logging {

  def sendVerificationEmail(emailAddress: UserEmailAddress): Future[Unit] = {
    val withCode = db.readWrite { implicit session =>
      userEmailAddressRepo.save(emailAddress.withVerificationCode(clock.now()))
    }
    emailConfirmationSender(withCode).imap(_ => ()) recoverWith {
      case error => {
        db.readWrite { implicit session =>
          userEmailAddressRepo.save(withCode.clearVerificationCode)
        }
        Future.failed(error)
      }
    }
  }

  def verifyEmailAddress(verificationCode: String)(implicit session: RWSession): Option[(UserEmailAddress, Boolean)] = { // returns Option(verifiedEmail, isVerifiedForTheFirstTime)
    userEmailAddressRepo.getByCode(verificationCode).map { emailAddress =>
      val isVerifiedForTheFirstTime = !emailAddress.verified
      (saveAsVerified(emailAddress), isVerifiedForTheFirstTime)
    }
  }

  def intern(userId: Id[User], address: EmailAddress, verified: Boolean = false)(implicit session: RWSession): Try[(UserEmailAddress, Boolean)] = {
    userEmailAddressRepo.getByAddress(address, excludeState = None) match {
      case Some(email) if email.verified && email.userId != userId => Failure(new UnavailableEmailAddressException(email, userId))
      case Some(email) if email.state != UserEmailAddressStates.INACTIVE => Success {
        val isNew = email.userId != userId
        val updatedEmail = if (isNew) email.copy(userId = userId).withAddress(address).clearVerificationCode else email.withAddress(address)
        val mustBeSaved = updatedEmail != email || (verified && !updatedEmail.verified)
        val interned = if (mustBeSaved) save(updatedEmail, verified) else email
        (interned, isNew)
      }

      case inactiveEmailOpt => Success {
        val newEmail = UserEmailAddress.create(userId, address).copy(id = inactiveEmailOpt.flatMap(_.id))
        (save(newEmail, verified), true)
      }
    }
  }

  private def save(emailAddress: UserEmailAddress, verified: Boolean)(implicit session: RWSession): UserEmailAddress = {
    if (verified) saveAsVerified(emailAddress)
    else userEmailAddressRepo.save(emailAddress)
  }

  def saveAsVerified(emailAddress: UserEmailAddress)(implicit session: RWSession): UserEmailAddress = {
    pendingInviteCommander.convertPendingLibraryInvites(emailAddress = emailAddress.address, userId = emailAddress.userId)
    pendingInviteCommander.convertPendingOrgInvites(emailAddress = emailAddress.address, userId = emailAddress.userId)
    val verifiedEmail = userEmailAddressRepo.save(emailAddress.copy(verifiedAt = Some(currentDateTime)))

    lazy val isPendingPrimaryEmail = {
      val pendingEmail = userValueRepo.getValueStringOpt(verifiedEmail.userId, UserValueName.PENDING_PRIMARY_EMAIL).map(EmailAddress(_))
      pendingEmail.exists(_ equalsIgnoreCase verifiedEmail.address)
    }

    val hasPrimaryEmail = userEmailAddressRepo.getPrimaryByUser(emailAddress.userId).isDefined

    if (hasPrimaryEmail || isPendingPrimaryEmail) {
      updatePrimaryEmailForUser(verifiedEmail)
    } else {
      verifiedEmail
    }
  }

  def setAsPrimaryEmail(primaryEmail: UserEmailAddress)(implicit session: RWSession): Unit = {
    if (primaryEmail.verified) {
      updatePrimaryEmailForUser(primaryEmail)
    } else {
      userValueRepo.setValue(primaryEmail.userId, UserValueName.PENDING_PRIMARY_EMAIL, primaryEmail.address)
    }
  }

  private def updatePrimaryEmailForUser(primaryEmail: UserEmailAddress)(implicit session: RWSession): UserEmailAddress = {
    require(primaryEmail.verified, s"Suggested primary email $primaryEmail is not verified")

    session.onTransactionSuccess { heimdalClient.setUserProperties(primaryEmail.userId, "$email" -> ContextStringData(primaryEmail.address.address)) }

    userValueRepo.clearValue(primaryEmail.userId, UserValueName.PENDING_PRIMARY_EMAIL)
    userEmailAddressRepo.getPrimaryByUser(primaryEmail.userId) match {
      case Some(existingPrimary) if existingPrimary.address equalsIgnoreCase primaryEmail.address => existingPrimary // this email is already marked as primary
      case existingPrimaryOpt => {
        existingPrimaryOpt.foreach(existingPrimary => userEmailAddressRepo.save(existingPrimary.copy(primary = false)))
        userEmailAddressRepo.save(primaryEmail.copy(primary = true))
      }
    }
  }

  def deactivate(emailAddress: UserEmailAddress, force: Boolean = false)(implicit session: RWSession): Try[Unit] = {
    val allEmails = userEmailAddressRepo.getAllByUser(emailAddress.userId)
    val isLast = !allEmails.exists(em => em.address != emailAddress.address)
    val isLastVerified = !allEmails.exists(em => em.address != emailAddress.address && em.verified)

    if (!force && isLast) Failure(new LastEmailAddressException(emailAddress))
    else if (!force && isLastVerified) Failure(new LastVerifiedEmailAddressException(emailAddress))
    else if (!force && emailAddress.primary) Failure(new PrimaryEmailAddressException(emailAddress))
    else Success {
      val pendingPrimary = userValueRepo.getValueStringOpt(emailAddress.userId, UserValueName.PENDING_PRIMARY_EMAIL).map(EmailAddress(_))
      if (pendingPrimary.exists(_ equalsIgnoreCase emailAddress.address)) {
        userValueRepo.clearValue(emailAddress.userId, UserValueName.PENDING_PRIMARY_EMAIL)
      }
      userEmailAddressRepo.save(emailAddress.withState(UserEmailAddressStates.INACTIVE))
    }
  }

  def addEmail(userId: Id[User], address: EmailAddress): Either[String, Unit] = {
    db.readWrite { implicit session =>
      intern(userId, address)
    } match {
      case Success((emailAddr, true)) =>
        if (!emailAddr.verified && !emailAddr.verificationSent) { sendVerificationEmail(emailAddr) }
        Right(())
      case Success((_, false)) => Left("email_already_added")
      case Failure(_: UnavailableEmailAddressException) => Left("permission_denied")
      case Failure(error) => throw error
    }
  }
}
