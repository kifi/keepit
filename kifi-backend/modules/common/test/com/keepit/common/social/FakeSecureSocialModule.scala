package com.keepit.common.social

import com.keepit.social.{SecureSocialModule, SecureSocialUserService}
import securesocial.core.UserService
import play.api.Play.current

case class FakeSecureSocialModule() extends SecureSocialModule {
  override def configure(): Unit = {
    new SecureSocialUserService().onStart()
    require(UserService.delegate.isDefined)
  }
}
