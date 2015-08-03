package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time.{ DEFAULT_DATE_TIME_ZONE, currentDateTime }
import com.keepit.heimdal.{ HeimdalServiceClient, ContextStringData }
import com.keepit.model._

@ImplementedBy(classOf[UserEmailAddressCommanderImpl])
trait UserEmailAddressCommander {
  def verifyEmailAddress(verificationCode: String)(implicit session: RWSession): Option[(UserEmailAddress, Boolean)]
  def saveAsVerified(emailAddress: UserEmailAddress)(implicit session: RWSession): UserEmailAddress
  def setAsPrimaryEmail(emailAddress: UserEmailAddress)(implicit session: RWSession): Unit
}

@Singleton
class UserEmailAddressCommanderImpl @Inject() (db: Database,
    userEmailAddressRepo: UserEmailAddressRepo,
    userValueRepo: UserValueRepo,
    userRepo: UserRepo,
    libraryInviteCommander: LibraryInviteCommander,
    organizationInviteCommander: OrganizationInviteCommander,
    heimdalClient: HeimdalServiceClient) extends UserEmailAddressCommander with Logging {

  def verifyEmailAddress(verificationCode: String)(implicit session: RWSession): Option[(UserEmailAddress, Boolean)] = { // returns Option(verifiedEmail, isVerifiedForTheFirstTime)
    userEmailAddressRepo.getByCode(verificationCode).map { emailAddress =>
      val isVerifiedForTheFirstTime = !emailAddress.verified
      (saveAsVerified(emailAddress), isVerifiedForTheFirstTime)
    }
  }

  def saveAsVerified(emailAddress: UserEmailAddress)(implicit session: RWSession): UserEmailAddress = {
    libraryInviteCommander.convertPendingInvites(emailAddress = emailAddress.address, userId = emailAddress.userId)
    organizationInviteCommander.convertPendingInvites(emailAddress = emailAddress.address, userId = emailAddress.userId)
    val verifiedEmail = userEmailAddressRepo.save(emailAddress.copy(state = UserEmailAddressStates.VERIFIED, verifiedAt = Some(currentDateTime)))

    lazy val isPendingPrimaryEmail = {
      val pendingEmail = userValueRepo.getValueStringOpt(verifiedEmail.userId, UserValueName.PENDING_PRIMARY_EMAIL).map(EmailAddress(_))
      pendingEmail.exists(_ == verifiedEmail.address)
    }

    val user = userRepo.get(verifiedEmail.userId)

    if (user.primaryEmail.isEmpty || isPendingPrimaryEmail) {
      updatePrimaryEmailForUser(user, verifiedEmail)
    }

    verifiedEmail
  }

  def setAsPrimaryEmail(primaryEmail: UserEmailAddress)(implicit session: RWSession): Unit = {
    val user = userRepo.get(primaryEmail.userId)
    updatePrimaryEmailForUser(user, primaryEmail)
  }

  private def updatePrimaryEmailForUser(user: User, primaryEmail: UserEmailAddress)(implicit session: RWSession): Unit = {
    require(primaryEmail.verified, s"Suggested primary email $primaryEmail is not verified")
    require(primaryEmail.userId == user.id.get, s"Suggested primary email $primaryEmail does not belong to $user")
    userValueRepo.clearValue(primaryEmail.userId, UserValueName.PENDING_PRIMARY_EMAIL)
    userRepo.save(user.copy(primaryEmail = Some(primaryEmail.address)))
    heimdalClient.setUserProperties(primaryEmail.userId, "$email" -> ContextStringData(primaryEmail.address.address))
  }
}
