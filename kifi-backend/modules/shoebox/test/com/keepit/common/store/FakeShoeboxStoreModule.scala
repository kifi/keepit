package com.keepit.common.store

import com.google.inject.{ Singleton, Provides }
import com.keepit.typeahead.{ InMemoryKifiUserTypeaheadStoreImpl, KifiUserTypeaheadStore, InMemorySocialUserTypeaheadStoreImpl, SocialUserTypeaheadStore }
import com.keepit.scraper.embedly.EmbedlyStore
import com.keepit.scraper.embedly.InMemoryEmbedlyStoreImpl

case class FakeShoeboxStoreModule() extends FakeStoreModule {

  @Provides @Singleton
  def s3ImageStore(s3ImageConfig: S3ImageConfig): S3ImageStore = FakeS3ImageStore(s3ImageConfig)

  @Provides @Singleton
  def kifiInstallationStore(): KifiInstallationStore = {
    new InMemoryKifiInstallationStoreImpl()
  }

  @Provides @Singleton
  def socialUserTypeaheadStore(): SocialUserTypeaheadStore = {
    new InMemorySocialUserTypeaheadStoreImpl()
  }

  @Provides @Singleton
  def kifiUserTypeaheadStore(): KifiUserTypeaheadStore = {
    new InMemoryKifiUserTypeaheadStoreImpl()
  }

  @Singleton @Provides
  def embedlyStore(): EmbedlyStore = {
    new InMemoryEmbedlyStoreImpl()
  }
}
