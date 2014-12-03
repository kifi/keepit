package com.keepit.common.oauth2

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ OAuth1TokenInfo, OAuth2TokenInfo }

import scala.concurrent.Future

sealed abstract class ProviderId(val id: String) {
  override def toString() = s"[ProviderId($id)]"
}
object ProviderIds {
  object Facebook extends ProviderId("facebook")
  object LinkedIn extends ProviderId("linkedin")
  object Twitter extends ProviderId("twitter") // revisit
  def toProviderId(id: String) = id match {
    case Facebook.id => Facebook
    case LinkedIn.id => LinkedIn
    case Twitter.id => Twitter
    case _ => throw new IllegalArgumentException(s"[toProviderId] id=$id not supported")
  }
}

case class ProviderUserId(val id: String) extends AnyVal

case class OAuth2AccessToken(val token: String) extends AnyVal {
  override def toString() = s"[OAuth2AccessToken] ${token.take(5)}...${token.takeRight(5)}"
}

object OAuth2AccessToken {
  import play.api.libs.json._
  implicit val format = Format(__.read[String].map(OAuth2AccessToken(_)),
    new Writes[OAuth2AccessToken] {
      def writes(o: OAuth2AccessToken) = JsString(o.token)
    })
}

case class UserProfileInfo(
  providerId: ProviderId,
  userId: ProviderUserId,
  name: String,
  emailOpt: Option[EmailAddress],
  firstNameOpt: Option[String],
  lastNameOpt: Option[String],
  pictureUrl: Option[java.net.URL])

object UserProfileInfo {
  implicit def toUserProfileInfo(identity: securesocial.core.Identity) = UserProfileInfo(
    ProviderIds.toProviderId(identity.identityId.providerId),
    ProviderUserId(identity.identityId.userId),
    identity.fullName,
    identity.email.map(EmailAddress(_)),
    Some(identity.firstName),
    Some(identity.lastName),
    identity.avatarUrl.map(new java.net.URL(_))
  )
}

trait OAuthProvider {
  def providerId: ProviderId
}

trait OAuth1Support extends OAuthProvider {
  def getUserProfileInfo(accessToken: OAuth1TokenInfo): Future[UserProfileInfo]
}

trait OAuth2Support extends OAuthProvider {
  def getUserProfileInfo(accessToken: OAuth2AccessToken): Future[UserProfileInfo]
  def exchangeLongTermToken(tokenInfo: OAuth2TokenInfo): Future[OAuth2TokenInfo]
}

trait ProviderRegistry {
  def get(providerId: ProviderId): Option[OAuthProvider]
  def getOAuth1Provider(providerId: ProviderId): Option[OAuth1Support]
  def getOAuth2Provider(providerId: ProviderId): Option[OAuth2Support]
}

@Singleton
class ProviderRegistryImpl @Inject() (
    airbrake: AirbrakeNotifier,
    fbProvider: FacebookOAuthProvider,
    lnkdProvider: LinkedInOAuthProvider) extends ProviderRegistry with Logging {

  def get(providerId: ProviderId): Option[OAuthProvider] = getOAuth2Provider(providerId)

  def getOAuth2Provider(providerId: ProviderId): Option[OAuth2Support] = {
    providerId match {
      case ProviderIds.Facebook => Some(fbProvider)
      case ProviderIds.LinkedIn => Some(lnkdProvider)
      case _ => None
    }
  }

  def getOAuth1Provider(providerId: ProviderId): Option[OAuth1Support] = throw new UnsupportedOperationException()
}
