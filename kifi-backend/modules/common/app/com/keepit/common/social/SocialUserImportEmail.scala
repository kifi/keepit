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
    db.readOnly { implicit s => emailRepo.getByAddressOpt(emailString) } match {
      case Some(email) =>
        if (email.userId != userId) {
          throw new IllegalStateException(s"email $email is not associated with user $userId")
        }
        log.info(s"email $email for user $userId already exists")
        email
      case None =>
        log.info(s"creating new email $emailString for user $userId")
        db.readWrite { implicit s => emailRepo.save(EmailAddress(userId = userId, address = emailString)) }
    }
  }

}
