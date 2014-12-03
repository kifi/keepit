package com.keepit.model

import com.keepit.common.oauth2.OAuth2AccessToken
import com.kifi.macros.json
import play.api.libs.oauth.RequestToken
import securesocial.core.{ OAuth1Info, OAuth2Info }

// replaces securesocial.core.OAuth2Info
@json case class OAuth2TokenInfo(
    accessToken: OAuth2AccessToken,
    tokenType: Option[String] = None,
    expiresIn: Option[Int] = None,
    refreshToken: Option[String] = None) {
  implicit def toOAuth2Info: OAuth2Info = OAuth2Info(accessToken.token, tokenType, expiresIn, refreshToken)
}

object OAuth2TokenInfo {
  implicit def toOAuth2Info(token: OAuth2TokenInfo): OAuth2Info =
    OAuth2Info(token.accessToken.token, token.tokenType, token.expiresIn, token.refreshToken)
  implicit def fromOAuth2Info(old: OAuth2Info): OAuth2TokenInfo =
    OAuth2TokenInfo(accessToken = OAuth2AccessToken(old.accessToken), old.tokenType, old.expiresIn, old.refreshToken)
}

@json case class OAuth1TokenInfo(token: String, secret: String)

object OAuth1TokenInfo {
  implicit def toRequestToken(token: OAuth1TokenInfo): RequestToken = RequestToken(token = token.token, secret = token.secret)

  implicit def toOAuth1Info(token: OAuth1TokenInfo): OAuth1Info = OAuth1Info(token.token, token.secret)
  implicit def fromOAuth1Info(old: OAuth1Info): OAuth1TokenInfo = OAuth1TokenInfo(old.token, old.secret)
}
