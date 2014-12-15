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
  // @json macro uses camelCase instead of underscore
  implicit val reads: Reads[OAuth2TokenInfo] = (
    (__ \ 'access_token).read[String] orElse (__ \ 'accessToken).read[String] fmap (OAuth2AccessToken.apply) and
    ((__ \ 'token_type).read[String] orElse (__ \ 'tokenType).read[String] orElse Reads.pure("")).fmap(s => if (s.isEmpty) None else Some(s)) and
    ((__ \ 'expires_in).read[Int] orElse (__ \ 'expiresIn).read[Int] orElse Reads.pure(-1)).fmap(i => if (i == -1) None else Some(i)) and
    ((__ \ 'refresh_token).read[String] orElse (__ \ 'refreshToken).read[String] orElse Reads.pure("")).fmap(s => if (s.isEmpty) None else Some(s))
  )(OAuth2TokenInfo.apply _)

  implicit val writes: Writes[OAuth2TokenInfo] = (
    (__ \ 'access_token).write[String] contramap (unlift(OAuth2AccessToken.unapply)) and
    (__ \ 'token_type).writeNullable[String] and
    (__ \ 'expires_in).writeNullable[Int] and
    (__ \ 'refresh_token).writeNullable[String]
  )(unlift(OAuth2TokenInfo.unapply))

}

@json case class OAuth1TokenInfo(token: String, secret: String)

object OAuth1TokenInfo {
  implicit def toRequestToken(token: OAuth1TokenInfo): RequestToken = RequestToken(token = token.token, secret = token.secret)

  implicit def toOAuth1Info(token: OAuth1TokenInfo): OAuth1Info = OAuth1Info(token.token, token.secret)
  implicit def fromOAuth1Info(old: OAuth1Info): OAuth1TokenInfo = OAuth1TokenInfo(old.token, old.secret)
}
