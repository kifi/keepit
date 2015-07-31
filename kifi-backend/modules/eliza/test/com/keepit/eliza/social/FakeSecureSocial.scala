package com.keepit.eliza.social

import securesocial.core.{ AuthenticationMethod, SocialUser, IdentityId }

object FakeSecureSocial {

  val FAKE_SID = "fake_sid"

  val FAKE_IDENTITY_ID = IdentityId(
    userId = "fake_user_id",
    providerId = "email" // not fake so that SocialNetworkType doesn't barf
  )

  val FAKE_AUTH_METHOD = AuthenticationMethod(
    method = "fake"
  )

  val FAKE_SOCIAL_USER = SocialUser(
    identityId = FAKE_IDENTITY_ID,
    firstName = "Fake",
    lastName = "User",
    fullName = "Fake User",
    email = Some("fake_user@fake.com"),
    avatarUrl = None,
    authMethod = FAKE_AUTH_METHOD
  )

}
