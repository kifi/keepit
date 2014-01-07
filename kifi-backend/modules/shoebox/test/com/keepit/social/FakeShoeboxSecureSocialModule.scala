package com.keepit.common.social

import com.keepit.social.{ProdShoeboxSecureSocialModule, ShoeboxSecureSocialModule, SecureSocialUserService}
import securesocial.core.UserService
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.controller.{ShoeboxActionAuthenticator, ActionAuthenticator}

case class FakeShoeboxSecureSocialModule() extends ShoeboxSecureSocialModule {
  override def configure(): Unit = {
    import play.api.Play.current
    new SecureSocialUserService().onStart()
    require(UserService.delegate.isDefined)
  }
}

