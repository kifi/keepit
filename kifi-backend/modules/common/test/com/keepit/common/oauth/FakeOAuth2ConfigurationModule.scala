package com.keepit.common.oauth

import com.google.inject.{ Singleton, Provides }
import com.keepit.model.{ OAuth1TokenInfo, OAuth2TokenInfo }
import com.keepit.social.RichSocialUser
import com.keepit.social.twitter.TwitterHandle
import play.api.libs.oauth.ConsumerKey
import play.api.libs.ws.WSResponse
import securesocial.core.IdentityId
import com.keepit.common.core._

import scala.concurrent.Future

trait FakeOAuthProvider[T, I <: RichIdentity] { self: OAuthProvider[T, I] =>

  private var identity: Future[I] = Future.failed(new IllegalStateException("No fake identity has been set."))
  def setIdentity(newIdentity: Future[I]) = { identity = newIdentity }
  def getRichIdentity(accessToken: T): Future[I] = identity
  def getIdentityId(accessToken: T): Future[IdentityId] = getRichIdentity(accessToken).imap(RichSocialUser(_).identityId)
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
class FakeTwitterOAuthProvider extends TwitterOAuthProvider with FakeOAuth1Provider[TwitterIdentity] {
  def getUserShow(accessToken: OAuth1TokenInfo, screenName: TwitterHandle): Future[TwitterUserShow] = Future.successful(TwitterUserShow(None, None, None, None))
}

case class FakeOAuth1ConfigurationModule() extends OAuth1ConfigurationModule {
  override def configure(): Unit = {
    bind[OAuth1ProviderRegistry].to[OAuth1ProviderRegistryImpl]
    bind[TwitterOAuthProvider].to[FakeTwitterOAuthProvider]
  }

  @Provides
  @Singleton
  def getOAuth1Configuration(): OAuth1Configuration = {
    import OAuth1Providers._
    val providerMap = Map(TWTR -> twtrConfigBuilder(ConsumerKey("cwXfTNd8iiKbWtXtszz9ADNmQ", "sO2GthBWUMhNG7WYp0gyBq4yLpSzVlJkdVPjfaxhTEe92ZfPS1"), ConsumerKey("17148682-0x4Qq6BU5GcX8NNmdDgpEPgbUORz0aRIwqPnynlTA", "8C3NU8zmy0FgHy9Ga7X8Xay2Yp1uB1EhQnpGsZ9ODa8vq")))
    OAuth1Configuration(providerMap)
  }
}

case class FakeOAuth2ConfigurationModule() extends OAuth2ConfigurationModule {
  def configure(): Unit = {
    bind[OAuth2ProviderRegistry].to[OAuth2ProviderRegistryImpl]
    bind[FacebookOAuthProvider].to[FakeFacebookOAuthProvider]
    bind[LinkedInOAuthProvider].to[FakeLinkedInOAuthProvider]
  }

  @Provides
  @Singleton
  def getOAuth2Configuration(): OAuth2Configuration = {
    import OAuth2Providers._
    val providerMap = Map(
      FB -> fbConfigBuilder("530357056981814", "cdb2939941a1147a4b88b6c8f3902745"),
      LNKD -> lnkdConfigBuilder("ovlhms1y0fjr", "5nz8568RERDuTNpu"),
      GOOG -> googConfigBuilder("991651710157.apps.googleusercontent.com", "vt9BrxsxM6iIG4EQNkm18L-m")
    )
    OAuth2Configuration(providerMap)
  }

}
