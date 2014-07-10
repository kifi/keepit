package com.keepit.common.social

import com.keepit.social.{ SecureSocialClientIds, ProdShoeboxSecureSocialModule, ShoeboxSecureSocialModule, SecureSocialUserService }
import securesocial.core.UserService
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.controller.{ ShoeboxActionAuthenticator, ActionAuthenticator }
import com.google.inject.{ Provides, Singleton }

case class FakeShoeboxSecureSocialModule() extends ShoeboxSecureSocialModule {
  override def configure(): Unit = {
    import play.api.Play.current
    new SecureSocialUserService().onStart()
    require(UserService.delegate.isDefined)
    install(FakeSocialGraphModule())
  }

  @Singleton
  @Provides
  def secureSocialClientIds: SecureSocialClientIds = SecureSocialClientIds("ovlhms1y0fjr", "530357056981814")
}

