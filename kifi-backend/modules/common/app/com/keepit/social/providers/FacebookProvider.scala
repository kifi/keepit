package com.keepit.social.providers

import com.keepit.FortyTwoGlobal
import com.keepit.common.logging.Logging
import com.keepit.common.oauth.{ OAuth2ProviderConfiguration, OAuth2AccessToken, OAuth2Constants, FacebookOAuthProviderImpl }
import com.keepit.common.oauth.adaptor.SecureSocialProviderHelper
import com.keepit.model.OAuth2TokenInfo
import com.keepit.social.UserIdentityProvider
import play.api.libs.json.{ JsNull, JsString, JsNumber, JsObject }

import play.api.libs.ws.{ WSResponse }
import play.api.{ Application }
import securesocial.core._
import net.codingwell.scalaguice.InjectorExtensions._
import com.keepit.common.core._

class FacebookProvider(app: Application)
    extends securesocial.core.providers.FacebookProvider(app) with UserIdentityProvider with SecureSocialProviderHelper with Logging {

  override protected def buildInfo(response: WSResponse): OAuth2Info = {
    try super.buildInfo(response) catch {
      case e: Throwable =>
        log.info(s"[securesocial] Failed to build oauth2 info. Response was ${response.body}")
        throw e
    }
  }

  override def buildTokenInfo(response: WSResponse): OAuth2TokenInfo = {
    log.info(s"[buildTokenInfo($id)] response.body=${response.body}")
    val parsed = response.body.split("&").map { kv =>
      val p = kv.split("=").take(2)
      p(0) -> (if (p.length == 2) {
        try { JsNumber(p(1).toInt) } catch {
          case _: Throwable => JsString(p(1))
        }
      } else JsNull)
    }.toMap
    OAuth2TokenInfo(
      OAuth2AccessToken(parsed.get(OAuth2Constants.AccessToken).map(_.as[String]).get),
      parsed.get(OAuth2Constants.TokenType).map(_.asOpt[String]).flatten,
      parsed.get(OAuth2Constants.ExpiresIn).map(_.asOpt[Int]).flatten,
      parsed.get(OAuth2Constants.RefreshToken).map(_.asOpt[String]).flatten
    ) tap { tk =>
        log.info(s"[buildInfo] token=$tk")
      }
  }

  lazy val global = app.global.asInstanceOf[FortyTwoGlobal] // fail hard
  lazy val provider = global.injector.instance[FacebookOAuthProviderImpl]
  def providerConfig: OAuth2ProviderConfiguration = provider.providerConfig
}
