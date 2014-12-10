package com.keepit.social.providers

import com.keepit.FortyTwoGlobal
import com.keepit.common.oauth._
import com.keepit.common.oauth.adaptor.SecureSocialOAuth2Adaptor
import com.keepit.social.UserIdentityProvider
import net.codingwell.scalaguice.InjectorExtensions._
import play.api.Application

class LinkedInProvider(app: Application)
    extends securesocial.core.OAuth2Provider(app)
    with UserIdentityProvider
    with SecureSocialOAuth2Adaptor {

  def id = LinkedInOAuthProvider.LinkedIn

  lazy val global = app.global.asInstanceOf[FortyTwoGlobal] // fail hard
  lazy val provider = global.injector.instance[LinkedInOAuthProviderImpl]
}
