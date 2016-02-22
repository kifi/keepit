package com.keepit.common.oauth

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ OAuth1TokenInfo, OAuth2TokenInfo }
import play.api.mvc.{ Result, Request }
import securesocial.core._

import scala.concurrent.Future

sealed abstract class ProviderId(val id: String) {
  override def toString() = s"[ProviderId($id)]"
}
object ProviderIds {
  object Facebook extends ProviderId("facebook")
  object LinkedIn extends ProviderId("linkedin")
  object Twitter extends ProviderId("twitter")
  object Slack extends ProviderId("slack")
  def toProviderId(id: String) = id match {
    case Facebook.id => Facebook
    case LinkedIn.id => LinkedIn
    case Twitter.id => Twitter
    case Slack.id => Slack
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
    pictureUrl: Option[String],
    profileUrl: Option[String])

case class OAuthTokenIdentityProvider[T, I <: RichIdentity](provider: OAuthProvider[T, I], token: T) {
  def getIdentityId: Future[IdentityId] = provider.getIdentityId(token)
  def getRichIdentity: Future[I] = provider.getRichIdentity(token)
}

trait OAuthProvider[T, I <: RichIdentity] {
  def providerId: ProviderId
  def getIdentityId(token: T): Future[IdentityId]
  def getRichIdentity(token: T): Future[I]
}

trait OAuth1Support[I <: RichIdentity] extends OAuthProvider[OAuth1TokenInfo, I]

trait OAuth2Support[I <: RichIdentity] extends OAuthProvider[OAuth2TokenInfo, I] {
  def providerConfig: OAuth2ProviderConfiguration
  def doOAuth[A]()(implicit request: Request[A]): Future[Either[Result, OAuth2TokenInfo]] // if not for weird TwitterProvider, we could move this to OAuthProvider hmmm
}

trait OAuth1ProviderRegistry {
  def get(providerId: ProviderId): Option[OAuth1Support[_ <: RichIdentity]]
}

@Singleton
class OAuth1ProviderRegistryImpl @Inject() (
    airbrake: AirbrakeNotifier,
    twtrProvider: TwitterOAuthProvider) extends OAuth1ProviderRegistry with Logging {
  def get(providerId: ProviderId): Option[OAuth1Support[_ <: RichIdentity]] = {
    providerId match {
      case ProviderIds.Twitter => Some(twtrProvider)
      case _ => None
    }
  }
}

trait OAuth2ProviderRegistry {
  def get(providerId: ProviderId): Option[OAuth2Support[_ <: RichIdentity]]
}

@Singleton
class OAuth2ProviderRegistryImpl @Inject() (
    airbrake: AirbrakeNotifier,
    fbProvider: FacebookOAuthProvider,
    lnkdProvider: LinkedInOAuthProvider) extends OAuth2ProviderRegistry with Logging {

  def get(providerId: ProviderId): Option[OAuth2Support[_ <: RichIdentity]] = {
    providerId match {
      case ProviderIds.Facebook => Some(fbProvider)
      case ProviderIds.LinkedIn => Some(lnkdProvider)
      case _ => None
    }
  }

}
