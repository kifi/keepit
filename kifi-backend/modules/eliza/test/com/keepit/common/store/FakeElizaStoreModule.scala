package com.keepit.common.store

import com.google.inject.{ Singleton, Provides }

case class FakeElizaStoreModule() extends FakeStoreModule {

  @Provides @Singleton
  def kifiInstallationStore(): KifiInstallationStore = {
    new InMemoryKifiInstallationStoreImpl()
  }

}
