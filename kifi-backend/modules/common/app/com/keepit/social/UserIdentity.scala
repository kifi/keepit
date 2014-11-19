package com.keepit.social

import com.keepit.common.db.Id
import com.keepit.model.User

import securesocial.core.SocialUser

class UserIdentity(
  val userId: Option[Id[User]],
  val socialUser: SocialUser)
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
  def apply(userId: Option[Id[User]], socialUser: SocialUser) = new UserIdentity(userId, socialUser)

  def unapply(u: UserIdentity) = Some(u.userId, u.socialUser)
}
