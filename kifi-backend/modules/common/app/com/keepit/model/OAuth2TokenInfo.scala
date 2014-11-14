package com.keepit.model

import com.keepit.common.oauth2.OAuth2AccessToken
import com.kifi.macros.json
import securesocial.core.OAuth2Info

// replaces securesocial.core.OAuth2Info
@json case class OAuth2TokenInfo(
  accessToken: OAuth2AccessToken,
  tokenType: Option[String] = None,
  expiresIn: Option[Int] = None,
  refreshToken: Option[String] = None)

object OAuth2TokenInfo {
  implicit def toOAuth2Info(token: OAuth2TokenInfo) =
    OAuth2Info(token.accessToken.token, token.tokenType, token.expiresIn, token.refreshToken)
  implicit def fromOAuth2Info(old: OAuth2Info) =
    OAuth2TokenInfo(accessToken = OAuth2AccessToken(old.accessToken), old.tokenType, old.expiresIn, old.refreshToken)
}
