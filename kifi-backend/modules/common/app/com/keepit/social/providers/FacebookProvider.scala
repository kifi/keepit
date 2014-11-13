package com.keepit.social.providers

import com.keepit.FortyTwoGlobal
import com.keepit.common.logging.Logging
import com.keepit.common.oauth2.FacebookOAuthProvider
import com.keepit.common.oauth2.adaptor.SecureSocialProviderHelper
import com.keepit.social.UserIdentityProvider

import play.api.libs.ws.{ WSResponse }
import play.api.{ Application }
import securesocial.core._
import net.codingwell.scalaguice.InjectorExtensions._

class FacebookProvider(app: Application)
    extends securesocial.core.providers.FacebookProvider(app) with UserIdentityProvider with SecureSocialProviderHelper with Logging {

  override protected def buildInfo(response: WSResponse): OAuth2Info = {
    try super.buildInfo(response) catch {
      case e: Throwable =>
        log.info(s"[securesocial] Failed to build oauth2 info. Response was ${response.body}")
        throw e
    }
  }

  lazy val global = app.global.asInstanceOf[FortyTwoGlobal] // fail hard
  lazy val provider = global.injector.instance[FacebookOAuthProvider]

}
