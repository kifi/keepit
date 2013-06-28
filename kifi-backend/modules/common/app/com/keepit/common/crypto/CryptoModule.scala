package com.keepit.common.crypto

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import play.api.Play._
import scala.Some

trait CryptoModule extends ScalaModule

case class ShoeboxCryptoModule() extends CryptoModule {
  def configure() {}

  @Singleton
  @Provides
  def userVoiceSSOTokenGenerator: UserVoiceTokenGenerator = {
    current.configuration.getString("userVoiceSSOToken") match {
      case Some(sso) =>
        new UserVoiceTokenGenerator {
          def createSSOToken(userId: String, displayName: String, email: String, avatarUrl: String): UserVoiceSSOToken =
            UserVoiceSSOToken(sso)
        }
      case None => new UserVoiceTokenGeneratorImpl()
    }
  }
}