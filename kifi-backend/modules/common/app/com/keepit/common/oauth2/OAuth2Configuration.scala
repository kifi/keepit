package com.keepit.common.oauth2

case class OAuth2ProviderConfiguration(name: String, authUrl: String, accessTokenUrl: String, exchangeTokenUrl: Option[String], scope: String, clientId: String, clientSecret: String)

object OAuth2Configuration {
  def build(name: String, authUrl: String, accessTokenUrl: String, scope: String) = (clientId: String, clientSecret: String) => {
    OAuth2ProviderConfiguration(name, authUrl, accessTokenUrl, Some(accessTokenUrl), scope, clientId, clientSecret)
  }
}

case class OAuth2Configuration(providers: Map[String, OAuth2ProviderConfiguration]) {
  def getProviderConfig(name: String): Option[OAuth2ProviderConfiguration] = providers.get(name)
}

