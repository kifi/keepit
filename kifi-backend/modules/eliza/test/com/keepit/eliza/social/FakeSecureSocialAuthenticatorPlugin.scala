package com.keepit.eliza.social

import com.keepit.social.SecureSocialAuthenticatorPlugin
import net.codingwell.scalaguice.ScalaModule
import securesocial.core.Authenticator
import com.keepit.common.time._

class FakeSecureSocialAuthenticatorPlugin extends SecureSocialAuthenticatorPlugin {

  override def save(authenticator: Authenticator): Either[Error, Unit] = Right(())

  override def delete(id: String): Either[Error, Unit] = Right(())

  override def find(id: String): Either[Error, Option[Authenticator]] = Right(Option(id).filter(_ == FakeSecureSocialAuthenticatorPlugin.FAKE_SID).map { _ =>
    new Authenticator(id, null, currentDateTime, currentDateTime, currentDateTime.plusYears(5))
  })

}

object FakeSecureSocialAuthenticatorPlugin {

  val FAKE_SID = "fake_sid"

}

case class FakeSecureSocialAuthenticatorPluginModule() extends ScalaModule {

  override def configure(): Unit = {
    bind[SecureSocialAuthenticatorPlugin].to[FakeSecureSocialAuthenticatorPlugin]
  }

}
