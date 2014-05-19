package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.model._

class SocialUserImportEmail @Inject() (
    db: Database,
    emailRepo: EmailAddressRepo) extends Logging {

  def importEmail(userId: Id[User], emailString: String): EmailAddress = {
    db.readWrite { implicit s =>
      val emails = emailRepo.getByAddress(emailString, excludeState = None)
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
        log.info(s"creating new email $emailString for user $userId")
        emailRepo.save(EmailAddress(userId = userId, address = emailString, state = EmailAddressStates.VERIFIED))
      }
    }
  }

}
