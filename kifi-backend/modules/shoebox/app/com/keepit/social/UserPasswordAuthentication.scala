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

  val pwdHasher = "bcrypt"

  def authenticate(userId: Id[User], providedCreds: String): Boolean = {
    val credsOpt = db.readOnlyMaster { implicit session =>
      userCredRepo.findByUserIdOpt(userId)
    }
    if (credsOpt.isEmpty) log.warn(s"[authenticate(${userId})] credentials not found")
    credsOpt map { creds =>
      Registry.hashers.get(pwdHasher) match {
        case None => throw new IllegalStateException(s"Password Hasher $pwdHasher not found")
        case Some(hasher) => hasher.matches(PasswordInfo(pwdHasher, creds.credentials, None), providedCreds)
      }
    } getOrElse false
  }

}
