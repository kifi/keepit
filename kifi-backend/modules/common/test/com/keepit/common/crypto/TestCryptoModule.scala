package com.keepit.common.crypto

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }

case class TestCryptoModule() extends CryptoModule {
  def configure() {}

  @Singleton
  @Provides
  def publicIdConfiguration: PublicIdConfiguration = {
    PublicIdConfiguration("testkey")
  }
}
