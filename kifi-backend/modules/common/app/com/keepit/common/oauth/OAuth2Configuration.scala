package com.keepit.common.oauth

import java.net.URL

case class OAuth2ProviderConfiguration(name: String, authUrl: URL, accessTokenUrl: URL, exchangeTokenUrl: Option[URL], scope: String, clientId: String, clientSecret: String)

object OAuth2Configuration {
  def build(name: String, authUrl: URL, accessTokenUrl: URL, scope: String) = (clientId: String, clientSecret: String) => {
    OAuth2ProviderConfiguration(name, authUrl, accessTokenUrl, Some(accessTokenUrl), scope, clientId, clientSecret)
  }

  implicit def urlToString(url: URL): String = url.toString
}

case class OAuth2Configuration(providers: Map[String, OAuth2ProviderConfiguration]) {
  def getProviderConfig(name: String): Option[OAuth2ProviderConfiguration] = providers.get(name)
}

