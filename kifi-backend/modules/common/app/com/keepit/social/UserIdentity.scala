package com.keepit.social

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ UserCred, User }

import securesocial.core.{ IdentityId, PasswordInfo, AuthenticationMethod, SocialUser }

sealed abstract class MaybeUserIdentity(val userId: Option[Id[User]], val socialUser: SocialUser) extends SocialUser(
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

// todo(Léo): can we make userId not optional in this case?
class UserIdentity(userId: Option[Id[User]], socialUser: SocialUser) extends MaybeUserIdentity(userId, socialUser)

object UserIdentity {
  def apply(userId: Option[Id[User]], socialUser: SocialUser) = new UserIdentity(userId, socialUser)
  def unapply(u: UserIdentity) = Some(u.userId, u.socialUser)

  def apply(user: User, emailAddress: EmailAddress, cred: Option[UserCred]): UserIdentity = {
    val passwordInfo = cred.map(actualCred => PasswordInfo(hasher = "bcrypt", password = actualCred.credentials))
    val socialUser = SocialUser(
      identityId = IdentityId(emailAddress.address, SocialNetworks.FORTYTWO.authProvider),
      firstName = user.firstName,
      lastName = user.lastName,
      fullName = user.fullName,
      email = Some(emailAddress.address),
      avatarUrl = None,
      authMethod = AuthenticationMethod.UserPassword,
      passwordInfo = passwordInfo
    )
    UserIdentity(user.id, socialUser)
  }
}

class NewUserIdentity(userId: Option[Id[User]], socialUser: SocialUser) extends MaybeUserIdentity(userId, socialUser)

object NewUserIdentity {
  def apply(userId: Option[Id[User]], socialUser: SocialUser) = new NewUserIdentity(userId, socialUser)
  def unapply(u: NewUserIdentity) = Some(u.userId, u.socialUser)
}
