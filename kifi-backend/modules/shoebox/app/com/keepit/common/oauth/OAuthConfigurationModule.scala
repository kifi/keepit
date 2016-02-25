package com.keepit.common.oauth

import java.net.URL

import com.google.inject.{ Singleton, Provides }
import com.keepit.common.oauth.OAuth2Providers._
import net.codingwell.scalaguice.ScalaModule
import play.api.libs.oauth.ConsumerKey

trait OAuthConfigurationModule extends ScalaModule

object OAuth1Providers {

  val TWTR = "twitter"

  val twtrConfigBuilder = OAuth1ProviderConfiguration.build(
    name = TWTR,
    requestTokenUrl = new URL("https://twitter.com/oauth/request_token"),
    accessTokenUrl = new URL("https://twitter.com/oauth/access_token"),
    authorizationUrl = new URL("https://twitter.com/oauth/authenticate")
  )
}

object OAuth2Providers {
  val FB = "facebook"
  val LNKD = "linkedin"
  val GOOG = "google"

  val fbConfigBuilder = OAuth2Configuration.build(
    name = FB,
    authUrl = new URL("https://www.facebook.com/v2.0/dialog/oauth"),
    accessTokenUrl = new URL("https://graph.facebook.com/v2.0/oauth/access_token"),
    scope = "email,user_friends"
  )

  val lnkdConfigBuilder = OAuth2Configuration.build(
    name = LNKD,
    authUrl = new URL("https://www.linkedin.com/uas/oauth2/authorization"),
    accessTokenUrl = new URL("https://www.linkedin.com/uas/oauth2/accessToken"),
    scope = "r_basicprofile,r_emailaddress"
  )

  val googConfigBuilder = OAuth2Configuration.build(
    name = GOOG,
    authUrl = new URL("https://accounts.google.com/o/oauth2/auth"),
    accessTokenUrl = new URL("https://accounts.google.com/o/oauth2/token"),
    scope = "email https://www.googleapis.com/auth/contacts.readonly"
  )
}

import OAuth1Providers._

case class DevOAuthConfigurationModule() extends OAuthConfigurationModule {
  def configure(): Unit = {
    bind[OAuth1ProviderRegistry].to[OAuth1ProviderRegistryImpl]
    bind[TwitterOAuthProvider].to[TwitterOAuthProviderImpl]

    bind[OAuth2ProviderRegistry].to[OAuth2ProviderRegistryImpl]
    bind[FacebookOAuthProvider].to[FacebookOAuthProviderImpl]
    bind[LinkedInOAuthProvider].to[LinkedInOAuthProviderImpl]

    bind[SlackOAuthProvider].to[SlackOAuthProviderImpl]
  }

  @Provides
  @Singleton
  def getOAuth1Configuration(): OAuth1Configuration = {
    val providerMap = Map(TWTR -> twtrConfigBuilder(ConsumerKey("cwXfTNd8iiKbWtXtszz9ADNmQ", "sO2GthBWUMhNG7WYp0gyBq4yLpSzVlJkdVPjfaxhTEe92ZfPS1"), ConsumerKey("17148682-0x4Qq6BU5GcX8NNmdDgpEPgbUORz0aRIwqPnynlTA", "8C3NU8zmy0FgHy9Ga7X8Xay2Yp1uB1EhQnpGsZ9ODa8vq")))
    OAuth1Configuration(providerMap)
  }

  @Provides
  @Singleton
  def getOAuth2Configuration(): OAuth2Configuration = {
    val providerMap = Map(
      FB -> fbConfigBuilder("530357056981814", "cdb2939941a1147a4b88b6c8f3902745"),
      LNKD -> lnkdConfigBuilder("ovlhms1y0fjr", "5nz8568RERDuTNpu"),
      GOOG -> googConfigBuilder("991651710157.apps.googleusercontent.com", "vt9BrxsxM6iIG4EQNkm18L-m")
    )
    OAuth2Configuration(providerMap)
  }
}

case class ProdOAuthConfigurationModule() extends OAuthConfigurationModule {
  def configure(): Unit = {
    bind[OAuth1ProviderRegistry].to[OAuth1ProviderRegistryImpl]
    bind[TwitterOAuthProvider].to[TwitterOAuthProviderImpl]

    bind[OAuth2ProviderRegistry].to[OAuth2ProviderRegistryImpl]
    bind[FacebookOAuthProvider].to[FacebookOAuthProviderImpl]
    bind[LinkedInOAuthProvider].to[LinkedInOAuthProviderImpl]

    bind[SlackOAuthProvider].to[SlackOAuthProviderImpl]
  }

  @Provides @Singleton
  def getOAuth1Configuration(): OAuth1Configuration = {
    val providerMap = Map(TWTR -> twtrConfigBuilder(ConsumerKey("9H4GYkjvd2nOsw2MqE8soWlQa", "cJN6wXEp7DAsTJXyS3LaWQcWOKLNlNIhFK2ajMcke7OOGe9njR"), ConsumerKey("17148682-kfWlx2yOgfFyQW0dvClWeNjdq806aJOW3cDH5FGyz", "tDPV7HnprgtZpQM8NnA2zTMrIJuJ6dJhwniY4XJetGp2X")))
    OAuth1Configuration(providerMap)
  }

  @Provides @Singleton
  def getOAuth2Configuration(): OAuth2Configuration = {
    val providerMap = Map(
      FB -> fbConfigBuilder("104629159695560", "352415703e40e9bb1b0329273fdb76a9"),
      LNKD -> lnkdConfigBuilder("r11loldy9zlg", "6XsgSLw60c0W2cId"),
      GOOG -> googConfigBuilder("572465886361.apps.googleusercontent.com", "heYhp5R2Q0lH26VkrJ1NAMZr")
    )
    OAuth2Configuration(providerMap)
  }
}
