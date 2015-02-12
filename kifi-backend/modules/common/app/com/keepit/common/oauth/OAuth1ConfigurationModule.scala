package com.keepit.common.oauth

import java.net.URL

import com.google.inject.{ Singleton, Provides }
import net.codingwell.scalaguice.ScalaModule
import play.api.libs.oauth.ConsumerKey

trait OAuth1ConfigurationModule extends ScalaModule

object OAuth1Providers {

  val TWTR = "twitter"

  val twtrConfigBuilder = OAuth1ProviderConfiguration.build(
    name = TWTR,
    requestTokenUrl = new URL("https://twitter.com/oauth/request_token"),
    accessTokenUrl = new URL("https://twitter.com/oauth/access_token"),
    authorizationUrl = new URL("https://twitter.com/oauth/authenticate")
  )

  val SUPPORTED = Seq(TWTR)

}

import OAuth1Providers._

case class DevOAuth1ConfigurationModule() extends OAuth1ConfigurationModule {
  def configure(): Unit = {
    bind[OAuth1ProviderRegistry].to[OAuth1ProviderRegistryImpl]
    bind[TwitterOAuthProvider].to[TwitterOAuthProviderImpl]
  }

  @Provides
  @Singleton
  def getOAuth1Configuration(): OAuth1Configuration = {
    val providerMap = Map(TWTR -> twtrConfigBuilder(ConsumerKey("cwXfTNd8iiKbWtXtszz9ADNmQ", "sO2GthBWUMhNG7WYp0gyBq4yLpSzVlJkdVPjfaxhTEe92ZfPS1"), ConsumerKey("17148682-0x4Qq6BU5GcX8NNmdDgpEPgbUORz0aRIwqPnynlTA", "8C3NU8zmy0FgHy9Ga7X8Xay2Yp1uB1EhQnpGsZ9ODa8vq")))
    OAuth1Configuration(providerMap)
  }
}

case class ProdOAuth1ConfigurationModule() extends OAuth1ConfigurationModule {
  def configure(): Unit = {
    bind[OAuth1ProviderRegistry].to[OAuth1ProviderRegistryImpl]
    bind[TwitterOAuthProvider].to[TwitterOAuthProviderImpl]
  }

  @Provides
  @Singleton
  def getOAuth1Configuration(): OAuth1Configuration = {
    val providerMap = Map(TWTR -> twtrConfigBuilder(ConsumerKey("9H4GYkjvd2nOsw2MqE8soWlQa", "cJN6wXEp7DAsTJXyS3LaWQcWOKLNlNIhFK2ajMcke7OOGe9njR"), ConsumerKey("17148682-kfWlx2yOgfFyQW0dvClWeNjdq806aJOW3cDH5FGyz", "tDPV7HnprgtZpQM8NnA2zTMrIJuJ6dJhwniY4XJetGp2X")))
    OAuth1Configuration(providerMap)
  }
}
