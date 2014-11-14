package com.keepit.common.oauth2.adaptor

import com.keepit.common.oauth2.{ OAuth2AccessToken, OAuthProvider, UserProfileInfo }
import securesocial.core._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.duration._
import scala.concurrent.Await

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

trait SecureSocialProviderHelper { self: OAuth2Provider =>
  def provider: OAuthProvider

  override def fillProfile(user: SocialUser): SocialUser = {
    val socialUserF = provider.getUserProfileInfo(OAuth2AccessToken(user.oAuth2Info.get.accessToken)) map { info =>
      SecureSocialAdaptor.toSocialUser(info, user.authMethod).copy(oAuth2Info = user.oAuth2Info)
    }
    Await.result(socialUserF, 5 minutes)
  }

}
