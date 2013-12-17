package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.common.store.S3ImageStore
import com.keepit.common.mail._
import com.keepit.common.db.Id
import securesocial.core._
import com.keepit.social._
import securesocial.core.providers.utils.GravatarHelper
import scala.Some
import securesocial.core.IdentityId
import scala.Some
import securesocial.core.PasswordInfo
import securesocial.core.IdentityId
import scala.Some
import securesocial.core.PasswordInfo
import com.keepit.social.UserIdentity
import play.api.mvc.{DiscardingCookie, Result, Request}
import play.api.libs.json.{Json, JsValue}
import com.keepit.controllers.core.routes
import securesocial.core.IdentityId
import scala.Some
import securesocial.core.PasswordInfo
import com.keepit.social.UserIdentity
import com.keepit.common.mail.GenericEmailAddress
import com.keepit.social.SocialId

class AuthCommander @Inject()(
  db: Database,
  clock: Clock,
  airbrakeNotifier: AirbrakeNotifier,
  userRepo: UserRepo,
  userCredRepo: UserCredRepo,
  socialRepo: SocialUserInfoRepo,
  emailAddressRepo: EmailAddressRepo,
  userValueRepo: UserValueRepo,
  passwordResetRepo: PasswordResetRepo,
  s3ImageStore: S3ImageStore,
  postOffice: LocalPostOffice,
  inviteCommander: InviteCommander
) {

  def saveUserPasswordIdentity(userIdOpt: Option[Id[User]], identityOpt: Option[Identity],
                               email: String, passwordInfo: PasswordInfo,
                               firstName: String = "", lastName: String = "", isComplete: Boolean): (UserIdentity, Id[User]) = {
    val fName = User.sanitizeName(if (isComplete || firstName.nonEmpty) firstName else email)
    val lName = User.sanitizeName(lastName)
    val newIdentity = UserIdentity(
      userId = userIdOpt,
      socialUser = SocialUser(
        identityId = IdentityId(email, SocialNetworks.FORTYTWO.authProvider),
        firstName = fName,
        lastName = lName,
        fullName = s"$fName $lName",
        email = Some(email),
        avatarUrl = GravatarHelper.avatarFor(email),
        authMethod = AuthenticationMethod.UserPassword,
        passwordInfo = Some(passwordInfo)
      ),
      allowSignup = true,
      isComplete = isComplete)

    UserService.save(newIdentity) // Kifi User is created here if it doesn't exist

    val userIdFromEmailIdentity = for {
      identity <- identityOpt
      socialUserInfo <- db.readOnly { implicit s =>
        socialRepo.getOpt(SocialId(newIdentity.identityId.userId), SocialNetworks.FORTYTWO)
      }
      userId <- socialUserInfo.userId
    } yield {
      UserService.save(UserIdentity(userId = Some(userId), socialUser = SocialUser(identity)))
      userId
    }

    val user = userIdFromEmailIdentity.orElse {
      db.readOnly { implicit s =>
        socialRepo.getOpt(SocialId(newIdentity.identityId.userId), SocialNetworks.FORTYTWO).map(_.userId).flatten
      }
    }

    (newIdentity, user.get)
  }


}
