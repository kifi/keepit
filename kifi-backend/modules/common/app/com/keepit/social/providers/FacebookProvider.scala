package com.keepit.social.providers

import com.keepit.social.UserIdentityProvider

import play.api.libs.ws.WSResponse
import play.api.{ Application, Logger }
import securesocial.core._

/**
 * A Facebook Provider
 */
class FacebookProvider(application: Application)
    extends securesocial.core.providers.FacebookProvider(application) with UserIdentityProvider {

  override protected def buildInfo(response: WSResponse): OAuth2Info = {
    try super.buildInfo(response) catch {
      case e: Throwable =>
        Logger.info(s"[securesocial] Failed to build oauth2 info. Response was ${response.body}")
        throw e
    }
  }
}
