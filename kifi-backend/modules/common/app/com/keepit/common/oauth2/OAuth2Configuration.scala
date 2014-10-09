package com.keepit.common.oauth2

case class OAuth2ProviderConfiguration(name: String, authUrl: String, accessTokenUrl: String, exchangeTokenUrl: Option[String], clientId: String, clientSecret: String, scope: String)

case class OAuth2Configuration(providers: Map[String, OAuth2ProviderConfiguration]) {
  def getProviderConfig(name: String): Option[OAuth2ProviderConfiguration] = providers.get(name)
}
