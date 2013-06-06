package com.keepit.common.social

import play.api.Play.current
import com.keepit.social.SecureSocialUserService
import securesocial.core.UserService
import com.tzavellas.sse.guice.ScalaModule

case class FakeSecureSocialUserServiceModule() extends ScalaModule {
  override def configure(): Unit = {
    new SecureSocialUserService().onStart()
    require(UserService.delegate.isDefined)
  }
}
