package com.keepit.social

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.oauth.{ EmailPasswordIdentity, RichIdentity }
import com.keepit.model.{ User, UserCred }
import securesocial.core._

sealed abstract class SocialUserHolder(socialUser: SocialUser) extends SocialUser(
  socialUser.identityId,
  socialUser.firstName,
  socialUser.lastName,
  socialUser.fullName,
  socialUser.email,
  socialUser.avatarUrl,
  socialUser.authMethod,
  socialUser.oAuth1Info,
  socialUser.oAuth2Info,
  socialUser.passwordInfo
)

sealed abstract class MaybeUserIdentity(val identity: RichIdentity, val userId: Option[Id[User]]) extends SocialUserHolder(RichIdentity.toSocialUser(identity))

class UserIdentity(identity: RichIdentity, userId: Option[Id[User]]) extends MaybeUserIdentity(identity, userId) {
  def withUserId(newUserId: Id[User]): UserIdentity = UserIdentity(identity, Some(newUserId))
}

object UserIdentity {
  def unapply(u: UserIdentity): Option[(RichIdentity, Option[Id[User]])] = Some(u.identity, u.userId)
  def apply(identity: RichIdentity, userId: Option[Id[User]] = None): UserIdentity = new UserIdentity(identity, userId)
  def apply(user: User, emailAddress: EmailAddress, cred: Option[UserCred]): UserIdentity = {
    val passwordInfo = cred.map(actualCred => PasswordInfo(hasher = "bcrypt", password = actualCred.credentials))
    UserIdentity(EmailPasswordIdentity(user.firstName, user.lastName, emailAddress, passwordInfo), Some(user.id.get))
  }
}

class NewUserIdentity(identity: RichIdentity, userId: Option[Id[User]]) extends MaybeUserIdentity(identity, userId)

object NewUserIdentity {
  def unapply(u: NewUserIdentity): Option[(RichIdentity, Option[Id[User]])] = Some(u.identity, u.userId)
  def apply(identity: RichIdentity, userId: Option[Id[User]]): NewUserIdentity = new NewUserIdentity(identity, userId)
}
