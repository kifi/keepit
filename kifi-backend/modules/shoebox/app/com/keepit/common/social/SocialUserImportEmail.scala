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
      emailRepo.getByAddressOpt(emailString, excludeState = None) match {
        case Some(email) =>
          if (email.userId != userId) {
            if (email.state == EmailAddressStates.INACTIVE) {
              emailRepo.save(email.copy(userId = userId, state = EmailAddressStates.UNVERIFIED))
            } else {
              throw new IllegalStateException(s"email $email is not associated with user $userId")
            }
          }
          log.info(s"email $email for user $userId already exists")
          email
        case None =>
          log.info(s"creating new email $emailString for user $userId")
          emailRepo.save(EmailAddress(userId = userId, address = emailString))
      }
    }
  }

}
