package com.keepit.social

import securesocial.core.UserService

case class FakeShoeboxSecureSocialModule() extends ShoeboxSecureSocialModule {
  override def configure(): Unit = {
    import play.api.Play.current
    new SecureSocialUserService().onStart()
    require(UserService.delegate.isDefined)
  }
}