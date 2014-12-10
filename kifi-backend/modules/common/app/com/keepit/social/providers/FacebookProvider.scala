package com.keepit.social.providers

import com.keepit.FortyTwoGlobal
import com.keepit.common.logging.Logging
import com.keepit.common.oauth.{ OAuth2ProviderConfiguration, FacebookOAuthProviderImpl, FacebookOAuth2ProviderHelper }
import com.keepit.common.oauth.adaptor.SecureSocialProviderHelper
import com.keepit.social.UserIdentityProvider
import net.codingwell.scalaguice.InjectorExtensions._
import play.api.Application
import play.api.libs.ws.WSResponse
import securesocial.core.OAuth2Info

class FacebookProvider(app: Application)
    extends securesocial.core.providers.FacebookProvider(app)
    with UserIdentityProvider
    with FacebookOAuth2ProviderHelper
    with SecureSocialProviderHelper
    with Logging {

  override protected def buildInfo(response: WSResponse): OAuth2Info = {
    try buildTokenInfo(response) catch {
      case e: Throwable =>
        log.info(s"[buildInfo($id)] Failed to build oauth2 info. Response was ${response.body}")
        throw e
    }
  }

  lazy val global = app.global.asInstanceOf[FortyTwoGlobal] // fail hard
  lazy val provider = global.injector.instance[FacebookOAuthProviderImpl]
  def providerConfig: OAuth2ProviderConfiguration = provider.providerConfig
}
