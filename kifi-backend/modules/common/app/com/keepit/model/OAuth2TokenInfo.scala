package com.keepit.model

import com.kifi.macros.json

// replaces securesocial.core.OAuth2Info
@json case class OAuth2TokenInfo(
  accessToken: String,
  tokenType: Option[String] = None,
  expiresIn: Option[Int] = None,
  refreshToken: Option[String] = None)

object OAuth2TokenInfo {
  implicit def toOAuth2Info(token: OAuth2TokenInfo): securesocial.core.OAuth2Info =
    securesocial.core.OAuth2Info(token.accessToken, token.tokenType, token.expiresIn, token.refreshToken)
}
