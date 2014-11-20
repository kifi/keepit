package com.keepit.social

import com.google.inject.Inject
import com.keepit.common.auth.AuthException
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ UserEmailAddress, UserEmailAddressRepo, User, UserCredRepo }
import com.keepit.social.providers.PasswordAuthentication
import securesocial.core.{ PasswordInfo, Registry }
import com.keepit.common.core._

class UserPasswordAuthentication @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    emailRepo: UserEmailAddressRepo,
    userCredRepo: UserCredRepo) extends PasswordAuthentication with Logging {

  val pwdHasher = "bcrypt"

  def authenticate(emailAddress: EmailAddress, creds: String): Either[Throwable, Id[User]] = {
    val maybeAddress = db.readOnlyMaster { implicit ro => emailRepo.getByAddressOpt(emailAddress) }
    maybeAddress match {
      case None =>
        Left(new AuthException("not_found"))
      case Some(email) =>
        if (validateCreds(email.userId, creds)) Right(email.userId) else Left(new AuthException("wrong_password"))
    }
  }

  private def validateCreds(userId: Id[User], providedCreds: String): Boolean = {
    val credsOpt = db.readOnlyMaster { implicit session =>
      userCredRepo.findByUserIdOpt(userId)
    }
    if (credsOpt.isEmpty) log.warn(s"[validateCreds(${userId})] creds not found")
    credsOpt map { creds =>
      Registry.hashers.get(pwdHasher) match {
        case None => throw new IllegalStateException(s"Password Hasher $pwdHasher not found")
        case Some(hasher) => hasher.matches(PasswordInfo(pwdHasher, creds.credentials, None), providedCreds)
      }
    } getOrElse false
  }

}
