package com.keepit.common.oauth2

import com.google.inject.{ Singleton, Provides }
import com.keepit.model.OAuth2TokenInfo

import scala.concurrent.Future

trait FakeOAuthProvider extends OAuthProvider {

  var profileInfo = UserProfileInfo(providerId, ProviderUserId("asdf"), "Foo Bar", None, None, None, None)
  var longTermTokenOpt: Option[OAuth2TokenInfo] = None

  def setProfileInfo(info: UserProfileInfo) { profileInfo = info }
  def setLongTermToken(token: OAuth2TokenInfo) { longTermTokenOpt = Some(token) }

  def exchangeLongTermToken(tokenInfo: OAuth2TokenInfo): Future[OAuth2TokenInfo] = Future.successful { longTermTokenOpt getOrElse tokenInfo }
  def getUserProfileInfo(accessToken: OAuth2AccessToken): Future[UserProfileInfo] = Future.successful { profileInfo }

}

@Singleton
class FakeFacebookOAuthProvider extends FacebookOAuthProvider with FakeOAuthProvider
@Singleton
class FakeLinkedInOAuthProvider extends LinkedInOAuthProvider with FakeOAuthProvider

case class FakeOAuth2ConfigurationModule() extends OAuth2ConfigurationModule {
  def configure(): Unit = {
    bind[ProviderRegistry].to[ProviderRegistryImpl]
    bind[FacebookOAuthProvider].to[FakeFacebookOAuthProvider]
    bind[LinkedInOAuthProvider].to[FakeLinkedInOAuthProvider]
  }

  @Provides
  @Singleton
  def getOAuth2Configuration(): OAuth2Configuration = {
    import OAuth2Providers._
    val providerMap = Map(
      FB -> fbConfigBuilder("530357056981814", "cdb2939941a1147a4b88b6c8f3902745"),
      GOOG -> googConfigBuilder("991651710157.apps.googleusercontent.com", "vt9BrxsxM6iIG4EQNkm18L-m")
    )
    OAuth2Configuration(providerMap)
  }

}
