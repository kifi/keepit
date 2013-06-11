package com.keepit.common.social

import net.codingwell.scalaguice.ScalaModule

import com.keepit.social.SecureSocialUserService

import play.api.Play.current
import securesocial.core.UserService

case class FakeSecureSocialUserServiceModule() extends ScalaModule {
  override def configure(): Unit = {
    new SecureSocialUserService().onStart()
    require(UserService.delegate.isDefined)
  }
}
