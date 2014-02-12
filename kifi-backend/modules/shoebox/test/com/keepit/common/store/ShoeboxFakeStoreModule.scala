package com.keepit.common.store

import com.google.inject.{Singleton, Provides}

case class ShoeboxFakeStoreModule() extends FakeStoreModule {

  @Provides @Singleton
  def s3ScreenshotStore: S3ScreenshotStore = FakeS3ScreenshotStore()

  @Provides @Singleton
  def s3ImageStore(s3ImageConfig: S3ImageConfig): S3ImageStore = FakeS3ImageStore(s3ImageConfig)

  @Provides @Singleton
  def kifiInstallationStore(): KifInstallationStore = {
    new InMemoryKifInstallationStoreImpl()
  }
}
