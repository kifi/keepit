package com.keepit.common.crypto

import com.keepit.common.util.Configuration
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import play.api.Play._

trait CryptoModule extends ScalaModule

case class ShoeboxCryptoModule() extends CryptoModule {
  def configure() {}

  @Singleton
  @Provides
  def publicIdConfiguration(config: Configuration): PublicIdConfiguration = {
    PublicIdConfiguration(config.getString("public-id.secret").get)
  }
}
