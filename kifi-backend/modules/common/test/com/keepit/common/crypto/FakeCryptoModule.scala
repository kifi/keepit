package com.keepit.common.crypto

import com.google.inject.{ Provides, Singleton }

case class FakeCryptoModule() extends CryptoModule {
  def configure() {}

  @Singleton
  @Provides
  def publicIdConfiguration: PublicIdConfiguration = {
    PublicIdConfiguration("testkey")
  }
}
