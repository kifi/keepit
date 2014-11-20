package com.keepit.social

import com.keepit.common.db.Id
import com.keepit.model.User

import securesocial.core.SocialUser

class UserIdentity(
  val userId: Option[Id[User]],
  val socialUser: SocialUser,
  val allowSignup: Boolean)
    extends SocialUser(
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

object UserIdentity {
  def apply(userId: Option[Id[User]], socialUser: SocialUser, allowSignup: Boolean = false) = new UserIdentity(userId, socialUser, allowSignup)

  def unapply(u: UserIdentity) = Some(u.userId, u.socialUser, u.allowSignup)
}
