package com.keepit.common.auth

import com.google.inject.{ Singleton, Inject }
import securesocial.core.{ Identity, IdentityId }

case class FakeLegacyUserServiceModule() extends LegacyUserServiceModule {
  def configure(): Unit = {
    bind[LegacyUserService].to[FakeLegacyUserService]
  }
}

class FakeLegacyUserService @Inject() () extends LegacyUserService {
  var identityOpt: Option[Identity] = None
  def find(id: IdentityId): Option[Identity] = identityOpt
}