package com.keepit.common.social

import com.google.inject.{ Provides, Singleton }
import com.keepit.social._
import com.keepit.social.providers.PasswordAuthentication
import securesocial.core._

case class FakeShoeboxAppSecureSocialModule() extends ShoeboxSecureSocialModule {
  // This has a Play Application dependency.
  override def configure(): Unit = {
    import play.api.Play.current
    new SecureSocialUserService().onStart()
    require(UserService.delegate.isDefined)
    install(FakeSocialGraphModule())
    bind[PasswordAuthentication].to[UserPasswordAuthentication]
  }

  @Singleton
  @Provides
  def secureSocialClientIds: SecureSocialClientIds = SecureSocialClientIds("ovlhms1y0fjr", "530357056981814")

}
