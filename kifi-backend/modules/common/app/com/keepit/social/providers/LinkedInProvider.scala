package com.keepit.social.providers

import com.keepit.FortyTwoGlobal
import com.keepit.common.oauth.{ LinkedInOAuthProvider, LinkedInOAuthProviderImpl }
import com.keepit.common.oauth.adaptor.SecureSocialProviderHelper
import com.keepit.social.UserIdentityProvider
import net.codingwell.scalaguice.InjectorExtensions._
import play.api.libs.ws.WSResponse
import play.api.{ Application, Logger }
import securesocial.core.OAuth2Info

class LinkedInProvider(app: Application)
    extends securesocial.core.OAuth2Provider(app) with UserIdentityProvider with SecureSocialProviderHelper {

  override def id = LinkedInOAuthProvider.LinkedIn

  override protected def buildInfo(response: WSResponse): OAuth2Info = {
    try super.buildInfo(response) catch {
      case e: Throwable =>
        Logger.info(s"[securesocial] Failed to build linkedin oauth2 info. Response was ${response.body}")
        throw e
    }
  }

  lazy val global = app.global.asInstanceOf[FortyTwoGlobal] // fail hard
  lazy val provider = global.injector.instance[LinkedInOAuthProviderImpl]
}
