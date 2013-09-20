package com.keepit.common.social

import com.keepit.social.{ShoeboxSecureSocialModule, SecureSocialUserService}
import securesocial.core.UserService
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.controller.{ShoeboxActionAuthenticator, ActionAuthenticator}

case class TestShoeboxSecureSocialModule() extends ShoeboxSecureSocialModule {
  override def configure(): Unit = {
    install(FakeAirbrakeModule())
    bind[ActionAuthenticator].to[ShoeboxActionAuthenticator]
    import play.api.Play.current
    new SecureSocialUserService().onStart()
    require(UserService.delegate.isDefined)
  }
}

