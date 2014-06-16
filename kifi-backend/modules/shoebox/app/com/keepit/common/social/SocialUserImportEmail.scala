package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.common.mail.EmailAddress

class SocialUserImportEmail @Inject() (
    db: Database,
    emailRepo: UserEmailAddressRepo) extends Logging {

  def importEmail(userId: Id[User], emailAddress: EmailAddress): UserEmailAddress = {
    db.readWrite { implicit s =>
      val emails = emailRepo.getByAddress(emailAddress, excludeState = None)
      emails.map { email =>
        if (email.userId != userId) {
          if (email.state == EmailAddressStates.VERIFIED) {
            throw new IllegalStateException(s"email ${email.address} of user ${email.userId} is VERIFIED but not associated with user $userId")
          } else if (email.state == EmailAddressStates.UNVERIFIED) {
            emailRepo.save(email.withState(EmailAddressStates.INACTIVE))
          }
          None
        } else {
          Some(email)
        }
      }.flatten.headOption.getOrElse {
        log.info(s"creating new email $emailAddress for user $userId")
        emailRepo.save(UserEmailAddress(userId = userId, address = emailAddress, state = EmailAddressStates.VERIFIED))
      }
    }
  }

}
