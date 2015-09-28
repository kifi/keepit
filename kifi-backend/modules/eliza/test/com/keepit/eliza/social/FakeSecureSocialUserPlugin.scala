package com.keepit.eliza.social

import com.keepit.social.{ UserIdentity, SecureSocialUserPlugin }
import net.codingwell.scalaguice.ScalaModule
import securesocial.core.{ IdentityId, SocialUser, Identity }
import securesocial.core.providers.Token

class FakeSecureSocialUserPlugin extends SecureSocialUserPlugin {

  override def find(id: IdentityId): Option[UserIdentity] = Option(id).filter(_ == FakeSecureSocial.FAKE_IDENTITY_ID).map { _ =>
    FakeSecureSocial.FAKE_USER_IDENTITY
  }

  override def findByEmailAndProvider(email: String, providerId: String): Option[SocialUser] = None

  override def deleteToken(uuid: String): Unit = {}

  override def save(identity: Identity): UserIdentity = FakeSecureSocial.FAKE_USER_IDENTITY

  override def save(token: Token): Unit = {}

  override def deleteExpiredTokens(): Unit = {}

  override def findToken(token: String): Option[Token] = None

}

case class FakeSecureSocialUserPluginModule() extends ScalaModule {

  override def configure(): Unit = {
    bind[SecureSocialUserPlugin].to[FakeSecureSocialUserPlugin]
  }

}
