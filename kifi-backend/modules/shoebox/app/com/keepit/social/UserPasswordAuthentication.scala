package com.keepit.social

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.{ User, UserCredRepo }
import com.keepit.social.providers.PasswordAuthentication
import securesocial.core.{ PasswordInfo, Registry }

class UserPasswordAuthentication @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    userCredRepo: UserCredRepo) extends PasswordAuthentication with Logging {

  def authenticate(userId: Id[User], providedCreds: String): Boolean = {
    val verifyPassword = db.readOnlyMaster { implicit session =>
      userCredRepo.verifyPassword(userId)
    }
    verifyPassword(providedCreds)
  }

}
