package com.keepit.common.oauth

import java.net.URL

import play.api.libs.oauth.ConsumerKey

case class OAuth1ProviderConfiguration(
  name: String,
  requestTokenUrl: URL,
  accessTokenUrl: URL,
  authorizationUrl: URL,
  key: ConsumerKey,
  accessToken: ConsumerKey)

object OAuth1ProviderConfiguration {
  def build(name: String, requestTokenUrl: URL, accessTokenUrl: URL, authorizationUrl: URL) = (key: ConsumerKey, accessToken: ConsumerKey) => {
    OAuth1ProviderConfiguration(name, requestTokenUrl, accessTokenUrl, authorizationUrl, key, accessToken)
  }
}

case class OAuth1Configuration(providers: Map[String, OAuth1ProviderConfiguration]) {
  def getProviderConfig(name: String): Option[OAuth1ProviderConfiguration] = providers.get(name)
}

case class OAuth2ProviderConfiguration(
  name: String,
  authUrl: URL,
  accessTokenUrl: URL,
  exchangeTokenUrl: Option[URL],
  scope: String,
  clientId: String,
  clientSecret: String)

object OAuth2Configuration {
  def build(name: String, authUrl: URL, accessTokenUrl: URL, scope: String) = (clientId: String, clientSecret: String) => {
    OAuth2ProviderConfiguration(name, authUrl, accessTokenUrl, Some(accessTokenUrl), scope, clientId, clientSecret)
  }

  implicit def urlToString(url: URL): String = url.toString
}

case class OAuth2Configuration(providers: Map[String, OAuth2ProviderConfiguration]) {
  def getProviderConfig(name: String): Option[OAuth2ProviderConfiguration] = providers.get(name)
}

