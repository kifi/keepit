package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time.{ DEFAULT_DATE_TIME_ZONE, currentDateTime }
import com.keepit.model.{ UserEmailAddress, UserEmailAddressRepo, UserEmailAddressStates }

@ImplementedBy(classOf[UserEmailAddressCommanderImpl])
trait UserEmailAddressCommander {
  def saveAsVerified(emailAddress: UserEmailAddress)(implicit session: RWSession): UserEmailAddress
}

@Singleton
class UserEmailAddressCommanderImpl @Inject() (db: Database,
    userEmailAddressRepo: UserEmailAddressRepo,
    libraryInviteCommander: LibraryInviteCommander,
    organizationInviteCommander: OrganizationInviteCommander) extends UserEmailAddressCommander with Logging {

  def saveAsVerified(emailAddress: UserEmailAddress)(implicit session: RWSession): UserEmailAddress = {
    libraryInviteCommander.convertPendingInvites(emailAddress = emailAddress.address, userId = emailAddress.userId)
    organizationInviteCommander.convertPendingInvites(emailAddress = emailAddress.address, userId = emailAddress.userId)
    userEmailAddressRepo.save(emailAddress.copy(state = UserEmailAddressStates.VERIFIED, verifiedAt = Some(currentDateTime)))
  }
}
