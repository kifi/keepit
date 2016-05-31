package com.keepit.common.oauth

import com.google.inject.{Provides, Singleton}
import com.keepit.common.core._
import com.keepit.slack.models.SlackAuthorizationResponse
import com.keepit.social.twitter.TwitterHandle
import play.api.libs.oauth.ConsumerKey
import play.api.libs.ws.WSResponse
import securesocial.core.IdentityId

import scala.concurrent.Future

trait FakeOAuthProvider[T, I <: RichIdentity] { self: OAuthProvider[T, I] =>

  private var identity: Future[I] = Future.failed(new IllegalStateException("No fake identity has been set."))
  def setIdentity(newIdentity: Future[I]) = { identity = newIdentity }
  def getRichIdentity(accessToken: T): Future[I] = identity
  def getIdentityId(accessToken: T): Future[IdentityId] = getRichIdentity(accessToken).imap(RichIdentity.toIdentityId)
}

trait FakeOAuth2Provider[I <: RichIdentity] extends FakeOAuthProvider[OAuth2TokenInfo, I] with OAuth2ProviderHelper { self: OAuth2Support[I] =>

  def oauth2Config: OAuth2Configuration = ???

  var oauth2Token = () => OAuth2TokenInfo(OAuth2AccessToken("fake-token"))
  def buildTokenInfo(response: WSResponse): OAuth2TokenInfo = oauth2Token.apply()

  var longTermTokenOpt: Option[OAuth2TokenInfo] = None
  def setLongTermToken(f: => OAuth2TokenInfo) { longTermTokenOpt = Some(f) }
  def exchangeLongTermToken(tokenInfo: OAuth2TokenInfo): Future[OAuth2TokenInfo] = Future.successful { longTermTokenOpt getOrElse tokenInfo }

}

trait FakeOAuth1Provider[I <: RichIdentity] extends FakeOAuthProvider[OAuth1TokenInfo, I] { self: OAuth1Support[I] => }

@Singleton
class FakeFacebookOAuthProvider extends FacebookOAuthProvider with FakeOAuth2Provider[FacebookIdentity]
@Singleton
class FakeLinkedInOAuthProvider extends LinkedInOAuthProvider with FakeOAuth2Provider[LinkedInIdentity]
@Singleton
class FakeSlackOAuthProvider extends SlackOAuthProvider with FakeOAuthProvider[SlackAuthorizationResponse, SlackIdentity]
@Singleton
class FakeTwitterOAuthProvider extends TwitterOAuthProvider with FakeOAuth1Provider[TwitterIdentity] {
  def getUserShow(accessToken: OAuth1TokenInfo, screenName: TwitterHandle): Future[TwitterUserShow] = Future.successful(TwitterUserShow(None, None, None, None, None, None, None))
}


case class FakeOAuthConfigurationModule() extends OAuthConfigurationModule {
  override def configure(): Unit = {
    bind[OAuth1ProviderRegistry].to[OAuth1ProviderRegistryImpl]
    bind[TwitterOAuthProvider].to[FakeTwitterOAuthProvider]

    bind[OAuth2ProviderRegistry].to[OAuth2ProviderRegistryImpl]
    bind[FacebookOAuthProvider].to[FakeFacebookOAuthProvider]
    bind[LinkedInOAuthProvider].to[FakeLinkedInOAuthProvider]

    bind[SlackOAuthProvider].to[FakeSlackOAuthProvider]
  }

  @Provides @Singleton
  def getOAuth1Configuration(): OAuth1Configuration = {
    import com.keepit.common.oauth.OAuth1Providers._
    val providerMap = Map(TWTR -> twtrConfigBuilder(ConsumerKey("cwXfTNd8iiKbWtXtszz9ADNmQ", "sO2GthBWUMhNG7WYp0gyBq4yLpSzVlJkdVPjfaxhTEe92ZfPS1"), ConsumerKey("17148682-0x4Qq6BU5GcX8NNmdDgpEPgbUORz0aRIwqPnynlTA", "8C3NU8zmy0FgHy9Ga7X8Xay2Yp1uB1EhQnpGsZ9ODa8vq")))
    OAuth1Configuration(providerMap)
  }

  @Provides @Singleton
  def getOAuth2Configuration(): OAuth2Configuration = {
    import com.keepit.common.oauth.OAuth2Providers._
    val providerMap = Map(
      FB -> fbConfigBuilder("530357056981814", "cdb2939941a1147a4b88b6c8f3902745"),
      LNKD -> lnkdConfigBuilder("ovlhms1y0fjr", "5nz8568RERDuTNpu"),
      GOOG -> googConfigBuilder("991651710157.apps.googleusercontent.com", "vt9BrxsxM6iIG4EQNkm18L-m")
    )
    OAuth2Configuration(providerMap)
  }
}
