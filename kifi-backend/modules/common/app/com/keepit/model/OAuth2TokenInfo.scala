package com.keepit.model

import com.keepit.common.oauth.OAuth2AccessToken
import com.kifi.macros.json
import play.api.libs.oauth.RequestToken
import securesocial.core.{ OAuth1Info, OAuth2Info }

// replaces securesocial.core.OAuth2Info
case class OAuth2TokenInfo(
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

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  implicit val format = (
    (__ \ 'access_token).format[String].inmap(OAuth2AccessToken.apply, unlift(OAuth2AccessToken.unapply)) and
    (__ \ 'token_type).formatNullable[String] and
    (__ \ 'expires_in).formatNullable[Int] and
    (__ \ 'refresh_token).formatNullable[String]
  )(OAuth2TokenInfo.apply _, unlift(OAuth2TokenInfo.unapply))
}

@json case class OAuth1TokenInfo(token: String, secret: String)

object OAuth1TokenInfo {
  implicit def toRequestToken(token: OAuth1TokenInfo): RequestToken = RequestToken(token = token.token, secret = token.secret)

  implicit def toOAuth1Info(token: OAuth1TokenInfo): OAuth1Info = OAuth1Info(token.token, token.secret)
  implicit def fromOAuth1Info(old: OAuth1Info): OAuth1TokenInfo = OAuth1TokenInfo(old.token, old.secret)
}
