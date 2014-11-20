package com.keepit.common.auth

import securesocial.core.{ Identity, IdentityId }

// the SecureSocial methods that we actually use
trait LegacyUserService {
  def find(id: IdentityId): Option[Identity]
}

