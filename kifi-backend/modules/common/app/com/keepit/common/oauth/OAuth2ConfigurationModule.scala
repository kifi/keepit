package com.keepit.common.oauth

import java.net.URL

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.util.Configuration
import net.codingwell.scalaguice.ScalaModule
import play.api.libs.oauth.ConsumerKey

trait OAuth2ConfigurationModule extends ScalaModule

object OAuth2Providers {
  // fb only for now; more work required
  val FB = "facebook"
  val GOOG = "google"

  val fbConfigBuilder = OAuth2Configuration.build(
    name = FB,
    authUrl = new URL("https://www.facebook.com/dialog/oauth"),
    accessTokenUrl = new URL("https://graph.facebook.com/oauth/access_token"),
    scope = "email"
  )

  val googConfigBuilder = OAuth2Configuration.build(
    name = GOOG,
    authUrl = new URL("https://accounts.google.com/o/oauth2/auth"),
    accessTokenUrl = new URL("https://accounts.google.com/o/oauth2/token"),
    scope = "email https://www.googleapis.com/auth/contacts.readonly"
  )

  val SUPPORTED = Seq(FB, GOOG)
}

import OAuth2Providers._

case class DevOAuth2ConfigurationModule() extends OAuth2ConfigurationModule {
  def configure(): Unit = {
    bind[OAuth2ProviderRegistry].to[OAuth2ProviderRegistryImpl]
    bind[FacebookOAuthProvider].to[FacebookOAuthProviderImpl]
    bind[LinkedInOAuthProvider].to[LinkedInOAuthProviderImpl]
  }

  @Provides
  @Singleton
  def getOAuth2Configuration(): OAuth2Configuration = {
    val providerMap = Map(
      FB -> fbConfigBuilder("530357056981814", "cdb2939941a1147a4b88b6c8f3902745"),
      GOOG -> googConfigBuilder("991651710157.apps.googleusercontent.com", "vt9BrxsxM6iIG4EQNkm18L-m")
    )
    OAuth2Configuration(providerMap)
  }

}

case class ProdOAuth2ConfigurationModule() extends OAuth2ConfigurationModule {
  def configure(): Unit = {
    bind[OAuth2ProviderRegistry].to[OAuth2ProviderRegistryImpl]
    bind[FacebookOAuthProvider].to[FacebookOAuthProviderImpl]
    bind[LinkedInOAuthProvider].to[LinkedInOAuthProviderImpl]
    bind[TwitterOAuthProvider].to[TwitterOAuthProviderImpl]
  }

  @Provides
  @Singleton
  def getOAuth2Configuration(): OAuth2Configuration = {
    val providerMap = Map(
      FB -> fbConfigBuilder("104629159695560", "352415703e40e9bb1b0329273fdb76a9"),
      GOOG -> googConfigBuilder("572465886361.apps.googleusercontent.com", "heYhp5R2Q0lH26VkrJ1NAMZr")
    )
    OAuth2Configuration(providerMap)
  }

}
