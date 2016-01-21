package com.keepit.eliza.social

import com.keepit.common.db.Id
import com.keepit.common.oauth.FacebookIdentity
import com.keepit.model.User
import com.keepit.social.{ UserIdentity, SocialId, SocialNetworkType }
import securesocial.core.{ AuthenticationMethod, SocialUser, IdentityId }

object FakeSecureSocial {

  val FAKE_SID = "fake_sid"

  val FAKE_ID = "fake_user_id"

  val FAKE_SOCIAL_ID = SocialId(FAKE_ID)

  val FAKE_NETWORK_TYPE = SocialNetworkType("facebook") // not fake so that it doesn't barf

  val FAKE_IDENTITY_ID = IdentityId(
    userId = FAKE_ID,
    providerId = FAKE_NETWORK_TYPE.name
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

  def fakeUserIdentity(userId: Option[Id[User]]) = UserIdentity(FacebookIdentity(FAKE_SOCIAL_USER), userId)

}
