package com.keepit.social.providers

import com.keepit.FortyTwoGlobal
import com.keepit.common.logging.Logging
import com.keepit.common.oauth.FacebookOAuthProviderImpl
import com.keepit.common.oauth.adaptor.SecureSocialOAuth2Adaptor
import com.keepit.social.UserIdentityProvider
import net.codingwell.scalaguice.InjectorExtensions._
import play.api.Application

class FacebookProvider(app: Application)
    extends securesocial.core.providers.FacebookProvider(app)
    with UserIdentityProvider
    with SecureSocialOAuth2Adaptor
    with Logging {
  lazy val global = app.global.asInstanceOf[FortyTwoGlobal] // fail hard
  lazy val provider = global.injector.instance[FacebookOAuthProviderImpl]
}
