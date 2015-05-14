package com.keepit.common.oauth

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.auth.AuthException
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ SocialUserInfo, OAuth1TokenInfo, OAuth2TokenInfo }
import com.keepit.social.{ SocialNetworks, SocialNetworkType, SocialId }
import play.api.libs.ws.{ WS, WSResponse }
import play.api.mvc.{ Result, Request }
import securesocial.core.IdentityProvider

import scala.concurrent.Future

sealed abstract class ProviderId(val id: String) {
  override def toString() = s"[ProviderId($id)]"
}
object ProviderIds {
  object Facebook extends ProviderId("facebook")
  object LinkedIn extends ProviderId("linkedin")
  object Twitter extends ProviderId("twitter")
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

case class UserHandle(underlying: String) extends AnyVal

case class UserProfileInfo(
    providerId: ProviderId,
    userId: ProviderUserId,
    name: String,
    emailOpt: Option[EmailAddress],
    firstNameOpt: Option[String],
    lastNameOpt: Option[String],
    handle: Option[UserHandle],
    pictureUrl: Option[java.net.URL],
    profileUrl: Option[java.net.URL]) {
  lazy val socialId: SocialId = {
    val id = userId.id.trim
    if (id.isEmpty) throw new Exception(s"empty social id for $toString")
    SocialId(id)
  }
  lazy val socialNetwork: SocialNetworkType = SocialNetworks.ALL.collectFirst { case n if n.name == providerId.id => n } get
}

object UserProfileInfo {
  implicit def toUserProfileInfo(identity: securesocial.core.Identity) = UserProfileInfo(
    ProviderIds.toProviderId(identity.identityId.providerId),
    ProviderUserId(identity.identityId.userId),
    identity.fullName,
    identity.email.map(EmailAddress(_)),
    Some(identity.firstName),
    Some(identity.lastName),
    None,
    identity.avatarUrl.map(new java.net.URL(_)),
    None
  )
}

trait OAuthProvider {
  def providerId: ProviderId
}

trait OAuth1Support extends OAuthProvider {
  def getUserProfileInfo(accessToken: OAuth1TokenInfo): Future[UserProfileInfo]
}

trait OAuth2Support extends OAuthProvider {
  def providerConfig: OAuth2ProviderConfiguration
  def buildTokenInfo(response: WSResponse): OAuth2TokenInfo
  def getUserProfileInfo(accessToken: OAuth2AccessToken): Future[UserProfileInfo]
  def exchangeLongTermToken(tokenInfo: OAuth2TokenInfo): Future[OAuth2TokenInfo]
  def doOAuth2[A]()(implicit request: Request[A]): Future[Either[Result, OAuth2TokenInfo]]
}

trait OAuth1ProviderRegistry {
  def get(providerId: ProviderId): Option[OAuth1Support]
}

@Singleton
class OAuth1ProviderRegistryImpl @Inject() (
    airbrake: AirbrakeNotifier,
    twtrProvider: TwitterOAuthProvider) extends OAuth1ProviderRegistry with Logging {
  def get(providerId: ProviderId): Option[OAuth1Support] = {
    providerId match {
      case ProviderIds.Twitter => Some(twtrProvider)
      case _ => None
    }
  }
}

trait OAuth2ProviderRegistry {
  def get(providerId: ProviderId): Option[OAuth2Support]
}

@Singleton
class OAuth2ProviderRegistryImpl @Inject() (
    airbrake: AirbrakeNotifier,
    fbProvider: FacebookOAuthProvider,
    lnkdProvider: LinkedInOAuthProvider) extends OAuth2ProviderRegistry with Logging {

  def get(providerId: ProviderId): Option[OAuth2Support] = {
    providerId match {
      case ProviderIds.Facebook => Some(fbProvider)
      case ProviderIds.LinkedIn => Some(lnkdProvider)
      case _ => None
    }
  }

}
