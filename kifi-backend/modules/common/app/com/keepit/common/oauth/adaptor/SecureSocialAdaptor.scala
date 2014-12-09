package com.keepit.common.oauth.adaptor

import com.keepit.common.oauth._
import securesocial.core.{ IdentityId, SocialUser, AuthenticationMethod }

object SecureSocialAdaptor {
  def toSocialUser(info: UserProfileInfo, authMethod: AuthenticationMethod): SocialUser = {
    SocialUser(
      identityId = IdentityId(info.userId.id, info.providerId.id),
      firstName = info.firstNameOpt getOrElse "",
      lastName = info.lastNameOpt getOrElse "",
      fullName = info.name,
      avatarUrl = info.pictureUrl.map(_.toString),
      authMethod = authMethod,
      email = info.emailOpt.map(_.address)
    )
  }
}
