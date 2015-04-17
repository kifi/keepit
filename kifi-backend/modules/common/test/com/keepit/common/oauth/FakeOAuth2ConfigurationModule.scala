package com.keepit.common.oauth

import com.google.inject.{ Singleton, Provides }
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ OAuth1TokenInfo, OAuth2TokenInfo }
import play.api.libs.json.JsValue
import play.api.libs.oauth.ConsumerKey
import play.api.libs.ws.WSResponse

import scala.concurrent.Future

trait FakeOAuthProvider extends OAuthProvider {

  var profileInfo = UserProfileInfo(providerId, ProviderUserId("asdf"), "Foo Bar", Some(EmailAddress("bar@foo.com")), Some("Foo"), Some("Bar"), None, Some(new java.net.URL("http://www.picture.com/foobar")), Some(new java.net.URL("http://www.profile.com/foobar")))
  def setProfileInfo(info: UserProfileInfo) { profileInfo = info }

  var profileInfoF = () => Future.successful(profileInfo)
  def setProfileInfoF(f: () => Future[UserProfileInfo]) { profileInfoF = f }

}

trait FakeOAuth2Provider extends FakeOAuthProvider with OAuth2ProviderHelper {

  def oauth2Config: OAuth2Configuration = ???

  var oauth2Token = () => OAuth2TokenInfo(OAuth2AccessToken("fake-token"))
  override def buildTokenInfo(response: WSResponse): OAuth2TokenInfo = oauth2Token.apply()

  var longTermTokenOpt: Option[OAuth2TokenInfo] = None
  def setLongTermToken(f: => OAuth2TokenInfo) { longTermTokenOpt = Some(f) }
  def exchangeLongTermToken(tokenInfo: OAuth2TokenInfo): Future[OAuth2TokenInfo] = Future.successful { longTermTokenOpt getOrElse tokenInfo }

  def getUserProfileInfo(accessToken: OAuth2AccessToken): Future[UserProfileInfo] = profileInfoF.apply()

}

trait FakeOAuth1Provider extends FakeOAuthProvider with OAuth1Support {
  def getUserProfileInfo(accessToken: OAuth1TokenInfo): Future[UserProfileInfo] = profileInfoF.apply()
}

@Singleton
class FakeFacebookOAuthProvider extends FacebookOAuthProvider with FakeOAuth2Provider
@Singleton
class FakeLinkedInOAuthProvider extends LinkedInOAuthProvider with FakeOAuth2Provider
@Singleton
class FakeTwitterOAuthProvider extends TwitterOAuthProvider with FakeOAuth1Provider {
  def getUserShow(accessToken: OAuth1TokenInfo, screenName: String): Future[TwitterUserShow] = Future.successful(TwitterUserShow(None, None, None, None))
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
