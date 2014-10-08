package com.keepit.common.oauth2

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.util.Configuration
import net.codingwell.scalaguice.ScalaModule

trait OAuth2ConfigurationModule extends ScalaModule {
}

object OAuth2Providers {
  // fb only for now; more work required
  val FB = "facebook"
  val fbAuthUrl = "https://www.facebook.com/dialog/oauth"
  val fbAccessTokenUrl = "https://graph.facebook.com/oauth/access_token"
  val fbScope = "email"
}

import OAuth2Providers._

case class DevOAuth2ConfigurationModule() extends OAuth2ConfigurationModule {
  def configure(): Unit = {}

  @Provides
  @Singleton
  def getOAuth2Configuration(config: Configuration): OAuth2Configuration = {
    val providerMap = Map(
      FB ->
        OAuth2ProviderConfiguration(
          name = FB,
          authUrl = fbAuthUrl,
          accessTokenUrl = fbAccessTokenUrl,
          exchangeTokenUrl = Some(fbAccessTokenUrl),
          clientId = "530357056981814",
          clientSecret = "cdb2939941a1147a4b88b6c8f3902745",
          scope = fbScope
        )
    )
    OAuth2Configuration(providerMap)
  }

}

case class ProdOAuth2ConfigurationModule() extends OAuth2ConfigurationModule {
  def configure(): Unit = {}

  @Provides
  @Singleton
  def getOAuth2Configuration(config: Configuration): OAuth2Configuration = {
    val providerMap = Map(
      FB ->
        OAuth2ProviderConfiguration(
          name = FB,
          authUrl = fbAuthUrl,
          accessTokenUrl = fbAccessTokenUrl,
          exchangeTokenUrl = Some(fbAccessTokenUrl),
          clientId = "104629159695560",
          clientSecret = "352415703e40e9bb1b0329273fdb76a9",
          scope = fbScope
        )
    )
    OAuth2Configuration(providerMap)
  }

}
