package com.keepit.common.oauth2

import com.keepit.common.mail.EmailAddress

import scala.concurrent.Future

class AuthException(msg: String, cause: Throwable) extends Throwable(msg, cause) {
  def this(msg: String) = this(msg, null)
}

sealed abstract class ProviderId(val id: String)
object ProviderIds {
  object Facebook extends ProviderId("facebook")
  object LinkedIn extends ProviderId("linkedin")
}

case class ProviderUserId(val id: String) extends AnyVal

case class OAuth2AccessToken(val token: String) extends AnyVal

case class UserProfileInfo(
  providerId: ProviderId,
  userId: ProviderUserId,
  name: String,
  emailOpt: Option[EmailAddress],
  firstNameOpt: Option[String],
  lastNameOpt: Option[String],
  pictureUrl: Option[java.net.URL])

trait OAuthProvider {

  def providerId: ProviderId

  def getUserProfileInfo(accessToken: OAuth2AccessToken): Future[UserProfileInfo]

}
