package com.keepit.eliza.social

import com.keepit.social.SecureSocialAuthenticatorPlugin
import net.codingwell.scalaguice.ScalaModule
import securesocial.core.{ IdentityId, Authenticator }
import com.keepit.common.time._

class FakeSecureSocialAuthenticatorPlugin extends SecureSocialAuthenticatorPlugin {

  override def save(authenticator: Authenticator): Either[Error, Unit] = Right(())

  override def delete(id: String): Either[Error, Unit] = Right(())

  override def find(id: String): Either[Error, Option[Authenticator]] = Right(Option(id).filter(_ == FakeSecureSocial.FAKE_SID).map { _ =>
    new Authenticator(id, FakeSecureSocial.FAKE_IDENTITY_ID, currentDateTime, currentDateTime, currentDateTime.plusYears(5))
  })

}

case class FakeSecureSocialAuthenticatorPluginModule() extends ScalaModule {

  override def configure(): Unit = {
    bind[SecureSocialAuthenticatorPlugin].to[FakeSecureSocialAuthenticatorPlugin]
  }

}
