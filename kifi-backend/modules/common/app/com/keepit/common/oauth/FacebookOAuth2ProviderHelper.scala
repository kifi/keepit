package com.keepit.common.oauth

import com.keepit.common.auth.AuthException
import com.keepit.model.OAuth2TokenInfo
import play.api.libs.json.{ JsNull, JsString, JsNumber }
import play.api.libs.ws.WSResponse

trait FacebookOAuth2ProviderHelper extends OAuth2ProviderHelper {

  // adapted from SecureSocial
  override def buildTokenInfo(response: WSResponse): OAuth2TokenInfo = {
    try {
      log.info(s"[buildTokenInfo(${providerConfig.name})] response.body=${response.body}")
      val parsed = response.body.split("&").map { kv =>
        val p = kv.split("=").take(2)
        p(0) -> (if (p.length == 2) {
          try {
            JsNumber(p(1).toInt)
          } catch {
            case _: Throwable => JsString(p(1))
          }
        } else JsNull)
      }.toMap
      log.info(s"[buildTokenInfo] parsed=$parsed")
      OAuth2TokenInfo(
        OAuth2AccessToken(parsed.get(OAuth2Constants.AccessToken).map(_.as[String]).get),
        parsed.get(OAuth2Constants.TokenType).map(_.asOpt[String]).flatten,
        parsed.get(OAuth2Constants.ExpiresIn).map(_.asOpt[Int]).flatten,
        parsed.get(OAuth2Constants.RefreshToken).map(_.asOpt[String]).flatten
      )
    } catch {
      case t: Throwable =>
        throw new AuthException(s"[buildTokenInfo(${providerConfig.name}] Token process failure. status=${response.status}; body=${response.body}", response, t)
    }
  }
}
