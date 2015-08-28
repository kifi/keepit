package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ OrganizationInviteRepo, OrganizationInviteStates, LibraryInviteRepo, User }

class PendingInviteCommander @Inject() (
    libraryInviteRepo: LibraryInviteRepo,
    organizationInviteRepo: OrganizationInviteRepo) {

  def convertPendingLibraryInvites(emailAddress: EmailAddress, userId: Id[User])(implicit session: RWSession): Unit = {
    libraryInviteRepo.getByEmailAddress(emailAddress, Set.empty) foreach { libInv =>
      libraryInviteRepo.save(libInv.copy(userId = Some(userId)))
    }
  }

  def convertPendingOrgInvites(emailAddress: EmailAddress, userId: Id[User])(implicit session: RWSession): Unit = {
    val hasOrgInvite = organizationInviteRepo.getByEmailAddress(emailAddress).map { invitation =>
      organizationInviteRepo.save(invitation.copy(userId = Some(userId)))
      invitation.state
    } contains OrganizationInviteStates.ACTIVE
  }

}
